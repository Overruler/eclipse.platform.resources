/**********************************************************************
 * Copyright (c) 2004 IBM Corporation and others. All rights reserved.   This
 * program and the accompanying materials are made available under the terms of
 * the Common Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors: 
 * IBM - Initial API and implementation
 **********************************************************************/
package org.eclipse.core.internal.refresh;

import java.util.ArrayList;
import org.eclipse.core.internal.utils.Messages;
import org.eclipse.core.resources.*;
import org.eclipse.core.resources.refresh.IRefreshMonitor;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.Job;
import org.osgi.framework.Bundle;

/**
 * The <code>PollingMonitor</code> is an <code>IRefreshMonitor</code> that
 * polls the filesystem rather than registering natively for callbacks.
 * 
 * The polling monitor operates in iterations that span multiple invocations
 * of the job's run method.  At the beginning of an iteration, a set of 
 * all resource roots is collected.  Each time the job runs, it removes items
 * from the set and searches for changes for a fixed period of time.
 * This ensures that the refresh job is broken into very small discrete
 * operations that do not interrupt the user's mainline activity.
 * 
 * @since 3.0
 */
public class PollingMonitor extends Job implements IRefreshMonitor {
	/**
	 * The maximum duration of a single polling iteration
	 */
	private static final long MAX_DURATION = 250;
	/**
	 * The amount of time that a changed root should remain
	 * hot.
	 */
	private static final long HOT_ROOT_DECAY = 90000;
	/**
	 * The minimum delay between executions of the polling monitor
	 */
	private static final long MIN_FREQUENCY = 4000;
	/**
	 * The roots of resources which should be polled
	 */
	private final ArrayList resourceRoots;
	/**
	 * The resources remaining to be refreshed in this iteration
	 */
	private final ArrayList toRefresh;
	/**
	 * The root that has most recently been out of sync
	 */
	private IResource hotRoot;
	/**
	 * The time the hot root was last refreshed
	 */
	private long hotRootTime;

	private final RefreshManager manager;
	/**
	 * True if this job has never been run. False otherwise.
	 */
	private boolean firstRun = true;

	/**
	 * Creates a new polling monitor.
	 */
	public PollingMonitor(RefreshManager manager) {
		super(Messages.refresh_pollJob);
		this.manager = manager;
		setPriority(Job.DECORATE);
		setSystem(true);
		resourceRoots = new ArrayList();
		toRefresh = new ArrayList();
	}

	/**
	 * Add the given root to the list of roots that need to be polled.
	 */
	public synchronized void monitor(IResource root) {
		resourceRoots.add(root);
		schedule(MIN_FREQUENCY);
	}

	/**
	 * Polls the filesystem under the root containers for changes.
	 */
	protected IStatus run(IProgressMonitor monitor) {
		//sleep until resources plugin has finished starting
		if (firstRun) {
			firstRun = false;
			Bundle bundle = Platform.getBundle(ResourcesPlugin.PI_RESOURCES);
			long waitStart = System.currentTimeMillis();
			while (bundle.getState() == Bundle.STARTING) {
				try {
					Thread.sleep(10000);
				} catch (InterruptedException e) {
					//ignore
				}
				//don't wait forever
				if ((System.currentTimeMillis() -waitStart) > 90000)
					break;
			}
		}
		long time = System.currentTimeMillis();
		//check to see if we need to start an iteration
		if (toRefresh.isEmpty()) {
			beginIteration();
			if (RefreshManager.DEBUG)
				System.out.println(RefreshManager.DEBUG_PREFIX + "New polling iteration on " + toRefresh.size() + " roots"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		final int oldSize = toRefresh.size();
		if (RefreshManager.DEBUG)
			System.out.println(RefreshManager.DEBUG_PREFIX + "started polling"); //$NON-NLS-1$
		//refresh the hot root if applicable
		if (time - hotRootTime > HOT_ROOT_DECAY)
			hotRoot = null;
		else if (hotRoot != null)
			poll(hotRoot);
		//process roots that have not yet been refreshed this iteration
		final long loopStart = System.currentTimeMillis();
		while (!toRefresh.isEmpty()) {
			if (monitor.isCanceled())
				break;
			poll((IResource) toRefresh.remove(toRefresh.size() - 1));
			//stop the iteration if we have exceed maximum duration
			if (System.currentTimeMillis() - loopStart > MAX_DURATION)
				break;
		}
		time = System.currentTimeMillis() - time;
		if (RefreshManager.DEBUG)
			System.out.println(RefreshManager.DEBUG_PREFIX + "polled " + (oldSize - toRefresh.size()) + " roots in " + time + "ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		//reschedule automatically - shouldRun will cancel if not needed
		//make sure it doesn't run more than 5% of the time
		long delay = Math.max(MIN_FREQUENCY, time * 20);
		if (RefreshManager.DEBUG)
			System.out.println(RefreshManager.DEBUG_PREFIX + "rescheduling polling job in: " + delay / 1000 + " seconds"); //$NON-NLS-1$ //$NON-NLS-2$
		//don't reschedule the job if the resources plugin has been shut down
		if (Platform.getBundle(ResourcesPlugin.PI_RESOURCES).getState() == Bundle.ACTIVE)
			schedule(delay);
		return Status.OK_STATUS;
	}

	/**
	 * Instructs the polling job to do one complete iteration of all workspace roots, and
	 * then discard itself. This is used when
	 * the refresh manager is first turned on if there is a native monitor installed (which
	 * don't handle changes that occurred while the monitor was turned off).
	 */
	void runOnce() {
		synchronized (this) {
			//add all roots to the refresh list, but not to the real set of roots
			//this will cause the job to never run again once it has exhaused
			//the set of roots to refresh
			IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
			for (int i = 0; i < projects.length; i++)
				toRefresh.add(projects[i]);
		}
		schedule(MIN_FREQUENCY);
	}

	private void poll(IResource resource) {
		if (resource.isSynchronized(IResource.DEPTH_INFINITE))
			return;
		//submit refresh request
		manager.refresh(resource);
		hotRoot = resource;
		hotRootTime = System.currentTimeMillis();
		if (RefreshManager.DEBUG)
			System.out.println(RefreshManager.DEBUG_PREFIX + "new hot root: " + resource); //$NON-NLS-1$
	}

	/* (non-Javadoc)
	 * @see Job#shouldRun
	 */
	public boolean shouldRun() {
		//only run if there is something to refresh
		return !resourceRoots.isEmpty() || !toRefresh.isEmpty();
	}

	/**
	 * Copies the resources to be polled into the list of resources
	 * to refresh this iteration. This method is synchronized to
	 * guard against concurrent access to the resourceRoots field.
	 */
	private synchronized void beginIteration() {
		toRefresh.addAll(resourceRoots);
		if (hotRoot != null)
			toRefresh.remove(hotRoot);
	}

	/*
	 * @see org.eclipse.core.resources.refresh.IRefreshMonitor#unmonitor(IContainer)
	 */
	public synchronized void unmonitor(IResource resource) {
		if (resource == null)
			resourceRoots.clear();
		else
			resourceRoots.remove(resource);
		if (resourceRoots.isEmpty())
			cancel();
	}
}