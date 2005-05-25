/*******************************************************************************
 * Copyright (c) 2002, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM - Initial API and implementation
 *******************************************************************************/

package org.eclipse.core.tools.resources;

import java.util.*;
import org.eclipse.core.internal.dtree.AbstractDataTreeNode;
import org.eclipse.core.internal.dtree.DataTreeNode;
import org.eclipse.core.internal.resources.*;
import org.eclipse.core.internal.watson.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.tools.*;
import org.eclipse.jface.action.*;
import org.eclipse.jface.text.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.ui.IActionBars;

/**
 * A spy view that shows detailed information about the workspace's element tree,
 * including space usage for the tree, and all resource metadata including markers,
 * sync info, and session properties.
 */
public class ElementTreeView extends SpyView implements IResourceChangeListener {

	class UpdateAction extends Action {

		class Counter implements Comparable {
			int count = 1;
			String name;

			Counter(String name) {
				this.name = name;
			}

			void add() {
				count++;
			}

			public int compareTo(Object o) {
				return ((Counter) o).getCount() - count;
			}

			int getCount() {
				return count;
			}

			String getName() {
				return name;
			}

		}

		int layerCount;
		int markerCount;
		int markerMemory;
		int nodeCount;
		int nonIdenticalStrings;
		int phantomCount;

		int resourceCount;
		DeepSize sessionPropertyMemory;
		List sortedList;
		int stringMemory;

		final Map strings = new HashMap();
		int syncInfoCount;
		int syncInfoMemory;
		int teamPrivateCount;

		//tree memory includes memory for strings and child array
		int treeNodeMemory;
		Workspace workspace = (Workspace) ResourcesPlugin.getWorkspace();

		UpdateAction() {
			super("Update view");
			this.setToolTipText("Update");
			this.setImageDescriptor(CoreToolsPlugin.createImageDescriptor("refresh.gif"));
		}

		void addToStringCount(String name) {
			if (name == null)
				return;
			//want to track the number of non-identical strings
			if (!DeepSize.ignore(name)) {
				nonIdenticalStrings++;
				//can't call sizeof because it will call isUnique again and weed out duplicates
				stringMemory += basicSizeof(name);

				//now want to count the number of duplicate equal but non-identical strings
				Counter counter = (Counter) strings.get(name);
				if (counter == null)
					strings.put(name, new Counter(name));
				else
					counter.add();
			}
		}

		void analyzeStrings() {
			sortedList = new ArrayList(strings.values());
			Collections.sort(sortedList);
		}

		void analyzeTrees() {
			// count the number of layers and the number of nodes
			// at each layer
			ElementTree tree = SpySupport.getOldestTree();
			for (this.layerCount = 0; tree != null; tree = tree.getParent()) {
				layerCount++;
				visit(tree);
			}
		}

		int basicSizeof(Map map) {
			if (map == null)
				return 0;

			//formula taken from BundleStats
			int count = (int) Math.round(44 + (16 + (map.size() * 1.25 * 4)) + (24 * map.size()));

			for (Iterator it = map.entrySet().iterator(); it.hasNext();) {
				Map.Entry entry = (Map.Entry) it.next();
				count += sizeof(entry.getKey());
				count += sizeof(entry.getValue());
			}
			return count;
		}

		int basicSizeof(MarkerAttributeMap markerMap) {
			int count = 20;//object header plus two slots
			Object[] elements = SpySupport.getElements(markerMap);
			if (elements != null) {
				count += 16 + 4 * elements.length;
				for (int i = 0; i < elements.length; i++)
					count += sizeof(elements[i]);
			}
			return count;
		}

		int basicSizeof(MarkerInfo info) {
			int count = 36;//object plus slots
			count += sizeof(info.getType());
			count += sizeof(info.getAttributes(false));
			return count;
		}

		int basicSizeof(MarkerSet markerSet) {
			if (markerSet == null)
				return 0;
			int count = 20;//object size plus two slots
			IMarkerSetElement[] elements = SpySupport.getElements(markerSet);
			if (elements != null) {
				count += 16 + 4 * elements.length;//size of elements array object
				for (int i = 0; i < elements.length; i++)
					if (elements[i] != null)
						count += sizeof(elements[i]);
			}
			return count;
		}

		int basicSizeof(String str) {
			return 36 + 2 * str.length();
		}

		void countResources() {
			// count the number of resources
			resourceCount = 0;
			markerCount = 0;
			teamPrivateCount = 0;
			phantomCount = 0;
			syncInfoCount = 0;
			IElementContentVisitor visitor = new IElementContentVisitor() {
				public boolean visitElement(ElementTree tree, IPathRequestor requestor, Object elementContents) {
					ResourceInfo info = (ResourceInfo) elementContents;
					if (info == null)
						return true;
					resourceCount++;
					if (info.isSet(ICoreConstants.M_TEAM_PRIVATE_MEMBER))
						teamPrivateCount++;
					if (info.isSet(ICoreConstants.M_PHANTOM))
						phantomCount++;
					MarkerSet markers = info.getMarkers();
					if (markers != null)
						markerCount += markers.size();
					Map syncInfo = SpySupport.getSyncInfo(info);
					if (syncInfo != null)
						syncInfoCount += syncInfo.size();
					return true;
				}
			};
			new ElementTreeIterator(workspace.getElementTree(), Path.ROOT).iterate(visitor);
		}

		void reset() {
			resourceCount = 0;
			teamPrivateCount = 0;
			phantomCount = 0;
			layerCount = 0;
			nodeCount = 0;

			treeNodeMemory = 0;
			stringMemory = 0;
			markerMemory = 0;
			syncInfoMemory = 0;
			sessionPropertyMemory = new DeepSize();

			strings.clear();
			DeepSize.reset();
			sortedList = null;
			nonIdenticalStrings = 0;
		}

		public void run() {
			super.run();
			reset();
			countResources();
			analyzeTrees();
			analyzeStrings();
			updateTextView();
			reset();
		}

		int sizeof(AbstractDataTreeNode node) {
			int count = 12;//three slots for an empty object
			if (node instanceof DataTreeNode) {
				//count memory for data
				count += 4;//reference to data
				Object data = ((DataTreeNode) node).getData();
				if (data instanceof ResourceInfo) {
					count += sizeof((ResourceInfo) data);
				}
			}
			//name
			count += 4;//reference to name
			//NOTE: space for name string is counted separately (see addToStringCount)

			//children
			count += 4;//reference to child array
			AbstractDataTreeNode[] children = node.getChildren();
			if (children != null && !DeepSize.ignore(children)) {
				count += 16 + (4 * children.length);//object header plus slots
			}
			return count;
		}

		/**
		 * All sizeof tests should go through this central method to weed out
		 * duplicates.
		 */
		int sizeof(Object object) {
			if (object == null || DeepSize.ignore(object))
				return 0;
			if (object instanceof String)
				return basicSizeof((String) object);
			if (object instanceof byte[])
				return 16 + ((byte[]) object).length;
			if (object instanceof MarkerAttributeMap)
				return basicSizeof((MarkerAttributeMap) object);
			if (object instanceof MarkerInfo)
				return basicSizeof((MarkerInfo) object);
			if (object instanceof MarkerSet)
				return basicSizeof((MarkerSet) object);
			if (object instanceof Integer)
				return 16;
			if (object instanceof Map)
				return basicSizeof((Map) object);
			if (object instanceof QualifiedName) {
				QualifiedName name = (QualifiedName) object;
				return 20 + sizeof(name.getQualifier()) + sizeof(name.getLocalName());
			}
			// unknown -- use deep size
			return 0;
		}

		int sizeof(ResourceInfo resourceInfo) {
			//object header plus all slots
			int count = 12 + (11 * 4);

			//markers
			markerMemory += sizeof(resourceInfo.getMarkers());

			//sync info
			syncInfoMemory += sizeof(SpySupport.getSyncInfo(resourceInfo));

			//session properties
			sessionPropertyMemory.deepSize(SpySupport.getSessionProperties(resourceInfo));

			if (resourceInfo.getClass() == RootInfo.class) {
				count += 4;//ref to property store
			}
			if (resourceInfo.getClass() == ProjectInfo.class) {
				count += 4 * 4;//four more slots
			}
			return count;
		}

		/**
		 * Sorts a set of entries whose keys are strings and values are Integer
		 * objects, in decreasing order by the integer value.
		 */
		private List sortEntrySet(Set set) {
			List result = new ArrayList();
			result.addAll(set);
			Collections.sort(result, new Comparator() {
				public int compare(Object arg0, Object arg1) {
					Integer value1 = (Integer) ((Map.Entry) arg0).getValue();
					Integer value2 = (Integer) ((Map.Entry) arg1).getValue();
					return value2.intValue() - value1.intValue();
				}
			});
			return result;
		}

		void updateTextView() {
			final StringBuffer buffer = new StringBuffer();
			buffer.append("Total resource count: " + prettyPrint(resourceCount) + "\n");
			buffer.append("\tTeam private: " + prettyPrint(teamPrivateCount) + "\n");
			buffer.append("\tPhantom: " + prettyPrint(phantomCount) + "\n");
			buffer.append("\tMarkers: " + prettyPrint(markerCount) + "\n");
			buffer.append("\tSyncInfo: " + prettyPrint(syncInfoCount) + "\n");
			buffer.append("Number of layers: " + layerCount + "\n");
			buffer.append("Number of nodes: " + prettyPrint(nodeCount) + "\n");
			buffer.append("Number of non-identical strings: " + prettyPrint(nonIdenticalStrings) + "\n");

			int sessionSize = sessionPropertyMemory.getSize();
			int totalMemory = treeNodeMemory + stringMemory + markerMemory + syncInfoMemory + sessionSize;
			buffer.append("Total memory used by nodes: " + prettyPrint(totalMemory) + "\n");
			buffer.append("\tNodes and ResourceInfo: " + prettyPrint(treeNodeMemory) + "\n");
			buffer.append("\tStrings: " + prettyPrint(stringMemory) + "\n");
			buffer.append("\tMarkers: " + prettyPrint(markerMemory) + "\n");
			buffer.append("\tSync info: " + prettyPrint(syncInfoMemory) + "\n");
			buffer.append("\tSession properties: " + prettyPrint(sessionSize) + "\n");
			//breakdown of session property size by class
			List sortedEntries = sortEntrySet(sessionPropertyMemory.getSizes().entrySet());
			for (Iterator it = sortedEntries.iterator(); it.hasNext();) {
				Map.Entry entry = (Map.Entry) it.next();
				buffer.append("\t\t" + entry.getKey() + ": " + prettyPrint(((Integer) entry.getValue()).intValue()) + "\n");
			}

			int max = 20;
			int savings = 0;
			buffer.append("The top " + max + " equal but non-identical strings are:\n");
			for (int i = 0; i < sortedList.size() && ((Counter) sortedList.get(i)).getCount() > 1; i++) {
				Counter c = (Counter) sortedList.get(i);
				if (i < max)
					buffer.append("\t" + c.getName() + "->" + prettyPrint(c.getCount()) + "\n");
				savings += ((c.getCount() - 1) * basicSizeof(c.getName()));
			}
			buffer.append("Potential savings of using unique strings: " + prettyPrint(savings) + "\n");

			//post changes to UI thread
			viewer.getControl().getDisplay().asyncExec(new Runnable() {
				public void run() {
					if (!viewer.getControl().isDisposed()) {
						IDocument doc = viewer.getDocument();
						doc.set(buffer.toString());
						viewer.setDocument(doc);
					}
				}
			});
		}

		void visit(AbstractDataTreeNode node) {
			//			if ("CVS".equals(node.getName())) {
			//				System.out.println("here");
			//			}
			nodeCount++;
			addToStringCount(node.getName());
			treeNodeMemory += sizeof(node);
			AbstractDataTreeNode[] children = node.getChildren();
			for (int i = 0; i < children.length; i++)
				visit(children[i]);
		}

		void visit(ElementTree tree) {
			AbstractDataTreeNode node = org.eclipse.core.internal.dtree.SpySupport.getRootNode(tree.getDataTree());
			visit(node);
		}
	}

	private IAction updateAction;

	// The JFace widget used for showing the Element Tree info.  
	protected TextViewer viewer;

	/**
	 * @see org.eclipse.ui.IWorkbenchPart#createPartControl(org.eclipse.swt.widgets.Composite)
	 */
	public void createPartControl(Composite parent) {

		viewer = new TextViewer(parent, SWT.V_SCROLL | SWT.H_SCROLL | SWT.WRAP | SWT.READ_ONLY);
		viewer.setDocument(new Document());

		IActionBars bars = getViewSite().getActionBars();

		final GlobalAction clearOutputAction = new ClearTextAction(viewer.getDocument());
		clearOutputAction.registerAsGlobalAction(bars);

		final GlobalAction selectAllAction = new SelectAllAction(viewer);
		selectAllAction.registerAsGlobalAction(bars);

		IMenuManager barMenuManager = getViewSite().getActionBars().getMenuManager();
		updateAction = new UpdateAction();
		barMenuManager.add(updateAction);

		// Delete action shortcuts are not captured by the workbench
		// so we need our key binding service to handle Delete keystrokes for us

		this.viewer.getControl().addKeyListener(new KeyAdapter() {
			public void keyPressed(KeyEvent e) {
				if (e.character == SWT.DEL)
					clearOutputAction.run();
			}
		});

		GlobalAction copyAction = new CopyTextSelectionAction(viewer);
		copyAction.registerAsGlobalAction(bars);

		bars.getToolBarManager().add(updateAction);
		bars.getToolBarManager().add(clearOutputAction);
		bars.updateActionBars();

		// creates a context menu with actions and adds it to the viewer control
		MenuManager menuMgr = new MenuManager();
		menuMgr.add(copyAction);
		menuMgr.add(clearOutputAction);
		Menu menu = menuMgr.createContextMenu(viewer.getControl());
		viewer.getControl().setMenu(menu);

		// add the resource change listener		
		ResourcesPlugin.getWorkspace().addResourceChangeListener(this);

		// populate the view with the initial data
		if (updateAction != null)
			updateAction.run();
	}

	/**
	 * @see org.eclipse.ui.IWorkbenchPart#dispose()
	 */
	public void dispose() {
		super.dispose();
		ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
		updateAction = null;
	}

	String prettyPrint(int i) {
		StringBuffer buf = new StringBuffer();
		for (;;) {
			if (i < 1000) {
				String val = Integer.toString(i);
				//pad with zeros if necessary
				if (buf.length() > 0) {
					if (val.length() < 2)
						buf.append('0');
					if (val.length() < 3)
						buf.append('0');
				}
				buf.append(val);
				return buf.toString();
			}
			if (i < 1000000) {
				String val = Integer.toString(i / 1000);
				//pad with zeros if necessary
				if (buf.length() > 0) {
					if (val.length() < 2)
						buf.append('0');
					if (val.length() < 3)
						buf.append('0');
				}
				buf.append(val);
				buf.append(',');
				i = i % 1000;
				continue;
			}
			buf.append(Integer.toString(i / 1000000));
			buf.append(',');
			i = i % 1000000;
		}
	}

	/**
	 * @see IResourceChangeListener#resourceChanged(IResourceChangeEvent)
	 */
	public void resourceChanged(IResourceChangeEvent event) {
		if (updateAction != null)
			updateAction.run();
	}

}