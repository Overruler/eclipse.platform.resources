package org.eclipse.core.internal.resources;/* * (c) Copyright IBM Corp. 2000, 2001. * All Rights Reserved. */
import java.util.*;/** * A specialized map implementation that is optimized for a  * small set of interned strings as keys.  The provided keys * MUST be instances of java.lang.String. */public class MarkerAttributeMap implements Map {
	protected Object[] elements = null;
	protected int count = 0;

	// 5 attribute keys, 5 attribute values
	protected static final int DEFAULT_SIZE = 10;
	protected static final int GROW_SIZE = 10;
public MarkerAttributeMap() {	super();}public MarkerAttributeMap(int initialCapacity) {	elements = new Object[Math.max(initialCapacity, 0)];}public MarkerAttributeMap(Map map) {
	super();
	initialize(map);
}
protected void initialize(Map map) {
	elements = new Object[Math.max(map.size(), DEFAULT_SIZE)];	putAll(map);
}/** * @see Map#containsKey */
public boolean containsKey(Object key) {	((String)key).intern();	if (elements == null || count == 0)
		return false;
	for (int i = 0; i < elements.length; i = i + 2)
		if (elements[i] == key)
			return true;
	return false;
}
/** * @see Map#containsValue */public boolean containsValue(Object value) {
	if (elements == null || count == 0)
		return false;
	for (int i = 1; i < elements.length; i = i + 2)
		if (elements[i] != null && elements[i].equals(value))
			return true;
	return false;
}
protected void grow() {
	Object[] expanded = new Object[elements.length + GROW_SIZE];
	System.arraycopy(elements, 0, expanded, 0, elements.length);
	elements = expanded;
}
/** * @see Map#get */public Object get(Object key) {
	((String)key).intern();	if (elements == null || count == 0)
		return null;
	for (int i = 0; i < elements.length; i = i + 2)
		if (elements[i] == key)
			return elements[i + 1];
	return null;
}
/** * @see Map#size */public int size() {
	return count;
}
/** * @see Map#remove */public Object remove(Object key) {
	((String)key).intern();	if (elements == null || count == 0)
		return null;
	for (int i = 0; i < elements.length; i = i + 2) {
		if (elements[i] == key) {
			elements[i] = null;
			Object result = elements[i + 1];
			elements[i + 1] = null;
			count--;
			return result;
		}
	}
	return null;
}
/** * @see Map#clear */public void clear() {
	elements = null;
	count = 0;
}
/** * @see Map#isEmpty */public boolean isEmpty() {
	return count == 0;
}
/** * @see Map#put */public Object put(Object key, Object value) {
	if (key == null)
		throw new NullPointerException();
	if (value == null)
		return remove(key);
	((String)key).intern();	
	// handle the case where we don't have any attributes yet
	if (elements == null)
		elements = new Object[DEFAULT_SIZE];
	if (count == 0) {
		elements[0] = key;
		elements[1] = value;
		count++;
		return null;
	}

	// replace existing value if it exists
	for (int i = 0; i < elements.length; i = i + 2) {
		if (elements[i] == key) {
			Object oldValue = elements[i + 1];
			elements[i + 1] = value;
			return oldValue;
		}
	}
	
	// otherwise add it to the list of elements.
	// grow if necessary
	if (elements.length <= (count * 2))
		grow();
	for (int i = 0; i < elements.length; i = i + 2)
		if (elements[i] == null) {
			elements[i] = key;
			elements[i + 1] = value;
			count++;
			return null;
		}	return null;
}
/** * @see Map#putAll */public void putAll(Map map) {
	for (Iterator i = map.keySet().iterator(); i.hasNext();) {
		Object key = i.next();
		Object value = map.get(key);
		put(key, value);
	}
}/** * Creates a new hash map with the same contents as this map. */private HashMap toHashMap() {	HashMap result = new HashMap(size());	for (int i = 0; i < elements.length; i = i + 2) {		if (elements[i] != null) {			result.put(elements[i], elements[i+1]);		}	}	return result;}/** * @see Map#values * This implementation does not conform properly to the specification * in the Map interface.  The returned collection will not be bound to * this map and will not remain in sync with this map. */public Collection values() {	Set result = new HashSet(size());	for (int i = 1; i < elements.length; i = i + 2) {		if (elements[i] != null) {			result.add(elements[i]);		}	}	return result;}/** * @see Map#entrySet * This implementation does not conform properly to the specification * in the Map interface.  The returned collection will not be bound to * this map and will not remain in sync with this map. */public Set entrySet() {	return toHashMap().entrySet();}/** * @see Map#keySet * This implementation does not conform properly to the specification * in the Map interface.  The returned collection will not be bound to * this map and will not remain in sync with this map. */public Set keySet() {	Set result = new HashSet(size());	for (int i = 0; i < elements.length; i = i + 2) {		if (elements[i] != null) {			result.add(elements[i]);		}	}	return result;}
}