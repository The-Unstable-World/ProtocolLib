package com.comphenix.protocol.async;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.bukkit.plugin.Plugin;

import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;

/**
 * Represents a handler for an asynchronous event.
 * 
 * @author Kristian
 */
public class AsyncListenerHandler {

	/**
	 * Signal an end to packet processing.
	 */
	private static final PacketEvent INTERUPT_PACKET = new PacketEvent(new Object());
	
	/**
	 * Called when the threads have to wake up for something important.
	 */
	private static final PacketEvent WAKEUP_PACKET = new PacketEvent(new Object());
	
	// Unique worker ID
	private static final AtomicInteger nextID = new AtomicInteger();
	
	// Default queue capacity
	private static int DEFAULT_CAPACITY = 1024;
	
	// Cancel the async handler
	private volatile boolean cancelled;
	
	// Number of worker threads
	private final AtomicInteger started = new AtomicInteger();
	
	// The packet listener
	private PacketListener listener;

	// The filter manager
	private AsyncFilterManager filterManager;
	private NullPacketListener nullPacketListener;
	
	// List of queued packets
	private ArrayBlockingQueue<PacketEvent> queuedPackets = new ArrayBlockingQueue<PacketEvent>(DEFAULT_CAPACITY);

	// List of cancelled tasks
	private final Set<Integer> stoppedTasks = new HashSet<Integer>();
	private final Object stopLock = new Object();
	
	// Minecraft main thread
	private Thread mainThread;
	
	public AsyncListenerHandler(Thread mainThread, AsyncFilterManager filterManager, PacketListener listener) {
		if (filterManager == null)
			throw new IllegalArgumentException("filterManager cannot be NULL");
		if (listener == null)
			throw new IllegalArgumentException("listener cannot be NULL");

		this.mainThread = mainThread;
		this.filterManager = filterManager;
		this.listener = listener;
	}
	
	public boolean isCancelled() {
		return cancelled;
	}

	public PacketListener getAsyncListener() {
		return listener;
	}

	/**
	 * Set the synchronized listener that has been automatically created.
	 * @param nullPacketListener - automatically created listener.
	 */
	void setNullPacketListener(NullPacketListener nullPacketListener) {
		this.nullPacketListener = nullPacketListener;
	}

	/**
	 * Retrieve the synchronized listener that was automatically created.
	 * @return Automatically created listener.
	 */
	PacketListener getNullPacketListener() {
		return nullPacketListener;
	}
	
	private String getPluginName() {
		return PacketAdapter.getPluginName(listener);
	}

	/**
	 * Retrieve the plugin associated with this async listener.
	 * @return The plugin.
	 */
	public Plugin getPlugin() {
		return listener != null ? listener.getPlugin() : null;
	}
	
	/**
	 * Cancel the handler.
	 */
	public void cancel() {
		// Remove the listener as quickly as possible
		close();
	}

	/**
	 * Queue a packet for processing.
	 * @param packet - a packet for processing.
	 * @throws IllegalStateException If the underlying packet queue is full.
	 */
	public void enqueuePacket(PacketEvent packet) {
		if (packet == null)
			throw new IllegalArgumentException("packet is NULL");
		
		queuedPackets.add(packet);
	}
	
	/**
	 * Create a worker that will initiate the listener loop. Note that using stop() to
	 * close a specific worker is less efficient than stopping an arbitrary worker.
	 * <p>
	 * <b>Warning</b>: Never call the run() method in the main thread.
	 */
	public AsyncRunnable getListenerLoop() {
		return new AsyncRunnable() {

			private final AtomicBoolean running = new AtomicBoolean();
			private volatile int id;
			
			@Override
			public int getID() {
				return id;
			}
			
			@Override
			public void run() {
				// Careful now
				if (running.compareAndSet(false, true)) {
					id = nextID.incrementAndGet();
					listenerLoop(id);
					
					synchronized (stopLock) {
						stoppedTasks.remove(id);
						notifyAll();
						running.set(false);
					}
					
				} else {
					throw new IllegalStateException(
							"This listener loop has already been started. Create a new instead.");
				}
			}
			
			@Override
			public boolean stop() throws InterruptedException {
				synchronized (stopLock) {
					if (!running.get())
						return false;

					stoppedTasks.add(id);
			
					// Wake up threads - we have a listener to stop
					for (int i = 0; i < getWorkers(); i++) {
						queuedPackets.offer(WAKEUP_PACKET);
					}
					
					waitForStops();
					return true;
				}
			}

			@Override
			public boolean isRunning() {
				return running.get();
			}
		};
	}
	
	/**
	 * Start a singler worker thread handling the asynchronous.
	 */
	public synchronized void start() {
		if (listener.getPlugin() == null)
			throw new IllegalArgumentException("Cannot start task without a valid plugin.");
		if (cancelled)
			throw new IllegalStateException("Cannot start a worker when the listener is closing.");
		
		filterManager.scheduleAsyncTask(listener.getPlugin(), getListenerLoop());
	}
	
	/**
	 * Start multiple worker threads for this listener.
	 * @param count - number of worker threads to start.
	 */
	public synchronized void start(int count) {
		for (int i = 0; i < count; i++)
			start();
	}
	
	/**
	 * Stop a worker thread.
	 */
	public synchronized void stop() {
		queuedPackets.add(INTERUPT_PACKET);
	}
	
	/**
	 * Stop the given amount of worker threads.
	 * @param count - number of threads to stop.
	 */
	public synchronized void stop(int count) {
		for (int i = 0; i < count; i++)
			stop();
	}
	
	/**
	 * Set the current number of workers. 
	 * <p>
	 * This method can only be called with a count of zero when the listener is closing.
	 * @param count - new number of workers.
	 */
	public synchronized void setWorkers(int count) {
		if (count < 0)
			throw new IllegalArgumentException("Number of workers cannot be less than zero.");
		if (count > DEFAULT_CAPACITY)
			throw new IllegalArgumentException("Cannot initiate more than " + DEFAULT_CAPACITY + " workers");
		if (cancelled && count > 0)
			throw new IllegalArgumentException("Cannot add workers when the listener is closing.");
		
		long time = System.currentTimeMillis();
		
		// Try to get to the correct count
		while (started.get() != count) {
			if (started.get() < count)
				start();
			else
				stop();
			
			// May happen if another thread is doing something similar to "setWorkers"
			if ((System.currentTimeMillis() - time) > 1000)
				throw new RuntimeException("Failed to set worker count.");
		}
	}
	
	/**
	 * Retrieve the current number of registered workers.
	 * <p>
	 * Note that the returned value may be out of data.
	 * @return Number of registered workers.
	 */
	public synchronized int getWorkers() {
		return started.get();
	}
	
	/**
	 * Wait until every tasks scheduled to stop has actually stopped.
	 * @return TRUE if the current listener should stop, FALSE otherwise.
	 * @throws InterruptedException - If the current thread was interrupted.
	 */
	private boolean waitForStops() throws InterruptedException {
		while (stoppedTasks.size() > 0 && !cancelled) {
			wait();
		}
		return cancelled;
	}
	
	// DO NOT call this method from the main thread
	private void listenerLoop(int workerID) {
		
		// Danger, danger!
		if (Thread.currentThread().getId() == mainThread.getId()) 
			throw new IllegalStateException("Do not call this method from the main thread.");
		if (cancelled)
			throw new IllegalStateException("Listener has been cancelled. Create a new listener instead.");

		try {
			// Wait if certain threads are stopping
			synchronized (stopLock) {
				if (waitForStops())
					return;
			}
			
			// Proceed
			started.incrementAndGet();
			
			mainLoop:
			while (!cancelled) {
				PacketEvent packet = queuedPackets.take();
				AsyncMarker marker = packet.getAsyncMarker();
				
				// Handle cancel requests
				if (packet == null || marker == null || packet == INTERUPT_PACKET) {
					return;
					
				} else if (packet == WAKEUP_PACKET) {
					// This is a bit slow, but it should be safe
					synchronized (stopLock) {
						// Are we the one who is supposed to stop?
						if (stoppedTasks.contains(workerID)) 
							return;
						if (waitForStops())
							return;
					}
				}
				
				// Here's the core of the asynchronous processing
				try {
					marker.setListenerHandler(this);
					marker.setWorkerID(workerID);
					
					if (packet.isServerPacket())
						listener.onPacketSending(packet);
					else
						listener.onPacketReceiving(packet);
					
				} catch (Throwable e) {
					// Minecraft doesn't want your Exception.
					filterManager.getLogger().log(Level.SEVERE, 
							"Unhandled exception occured in onAsyncPacket() for " + getPluginName(), e);
				}
				
				// Now, get the next non-cancelled listener
				for (; marker.getListenerTraversal().hasNext(); ) {
					AsyncListenerHandler handler = marker.getListenerTraversal().next().getListener();
					
					if (!handler.isCancelled()) {
						handler.enqueuePacket(packet);
						continue mainLoop;
					}
				}
				
				// There are no more listeners - queue the packet for transmission
				filterManager.signalPacketUpdate(packet);
				filterManager.signalProcessingDone(packet);
			}
			
		} catch (InterruptedException e) {
			// We're done
		} finally {
			// Clean up
			started.decrementAndGet();
			close();
		}
	}
	
	private synchronized void close() {
		// Remove the listener itself
		if (!cancelled) {
			filterManager.unregisterAsyncHandlerInternal(this);
			cancelled = true;
			
			// Tell every uncancelled thread to end
			stopThreads();
		}
	}
	
	/**
	 * Use the poision pill method to stop every worker thread.
	 */
	private void stopThreads() {
		// Poison Pill Shutdown
		queuedPackets.clear();
		stop(started.get());
		
		// Individual shut down is irrelevant now
		synchronized (stopLock) {
			notifyAll();
		}
	}
}
