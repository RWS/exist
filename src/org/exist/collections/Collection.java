/*
 *  Collection.java - eXist Open Source Native XML Database
 *  Copyright (C) 2001-03 Wolfgang M. Meier
 *  wolfgang@exist-db.org
 *  http://exist.sourceforge.net
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 * 
 * $Id$
 */
package org.exist.collections;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.TreeMap;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.apache.log4j.Logger;
import org.apache.xml.resolver.tools.CatalogResolver;
import org.exist.EXistException;
import org.exist.Indexer;
import org.exist.collections.triggers.DocumentTrigger;
import org.exist.collections.triggers.Trigger;
import org.exist.collections.triggers.TriggerException;
import org.exist.dom.BinaryDocument;
import org.exist.dom.DocumentImpl;
import org.exist.dom.DocumentSet;
import org.exist.security.Group;
import org.exist.security.Permission;
import org.exist.security.PermissionDeniedException;
import org.exist.security.SecurityManager;
import org.exist.security.User;
import org.exist.storage.DBBroker;
import org.exist.storage.IndexSpec;
import org.exist.storage.UpdateListener;
import org.exist.storage.cache.Cacheable;
import org.exist.storage.index.BFile;
import org.exist.storage.io.VariableByteInput;
import org.exist.storage.io.VariableByteOutputStream;
import org.exist.storage.lock.Lock;
import org.exist.storage.lock.ReentrantReadWriteLock;
import org.exist.storage.txn.Txn;
import org.exist.util.Configuration;
import org.exist.util.LockException;
import org.exist.util.SyntaxException;
import org.exist.util.hashtable.ObjectHashSet;
import org.exist.util.serializer.DOMStreamer;
import org.exist.xquery.Constants;
import org.w3c.dom.Node;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;

/**
 * This class represents a collection in the database. A collection maintains a list of 
 * sub-collections and documents, and provides the methods to store/remove resources.
 * 
 * Collections are shared between {@link org.exist.storage.DBBroker} instances. The caller
 * is responsible to lock/unlock the collection. Call {@link DBBroker#openCollection(String, int)}  
 * to get a collection with a read or write lock and {@link #release()} to release the lock.
 *  
 * @author wolf
 */
public  class Collection extends Observable

implements Comparable, EntityResolver, Cacheable {


    public Collection(){
        
    }

	private final static Logger LOG = Logger.getLogger(Collection.class);

	private final static int VALIDATION_ENABLED = 0;
	private final static int VALIDATION_AUTO = 1;
	private final static int VALIDATION_DISABLED = 2;
    
    public final static short UNKNOWN_COLLECTION_ID = -1;

	// Internal id 
	private short collectionId = UNKNOWN_COLLECTION_ID;

	// the documents contained in this collection
	private Map documents = new TreeMap();
	
	// the name of this collection
	private String name;

	// the permissions assigned to this collection
	private Permission permissions = new Permission(0755);

	// stores child-collections with their storage address
	private ObjectHashSet subcollections = new ObjectHashSet(19);

	// Storage address of the collection in the BFile
	private long address = BFile.UNKNOWN_ADDRESS;

	// creation time
	private long created = 0;

	private CatalogResolver resolver;

	private Observer[] observers = null;

    /**
     *  
     * @uml.property name="configuration"
     * @uml.associationEnd multiplicity="(0 1)"
     */
    private CollectionConfiguration configuration = null;

	
	private boolean collectionConfEnabled = true;
	private boolean triggersEnabled = true;

	// fields required by the collections cache
	private int refCount = 0;
	private int timestamp = 0;
	
	private Lock lock = null;
	
	/** user-defined Reader */
	private XMLReader userReader = null;
	
    /** is this a temporary collection? */
    private boolean isTempCollection = false;
    
	public Collection(String name) {
		setName(name);
		lock = new ReentrantReadWriteLock(name);
	}

	public void setName(String name) {
        isTempCollection = name.equals(DBBroker.TEMP_COLLECTION);
	    this.name = name;
	}
	
	public Lock getLockOld() {
		return getLock();
	}

    public Lock getLock() {
        return lock;
    }

    /**
	 *  Add a new sub-collection to the collection.
	 *
	 *@param  name
	 */
	public void addCollection(DBBroker broker, Collection child, boolean isNew) {
        //TODO : use dedicated function in XmldbURI
		final int p = child.name.lastIndexOf("/") + 1;
		final String childName = child.name.substring(p);
		if (!subcollections.contains(childName))
			subcollections.add(childName);
		if (isNew) {
			CollectionConfiguration config = getConfiguration(broker);
			if (config != null)
				child.permissions.setPermissions(config.getDefCollPermissions());
		}
	}

	public boolean hasChildCollection(String name) {
		return subcollections.contains(name);
	}
	
    /**
     * Returns true if this is a temporary collection. By default,
     * the temporary collection is in /db/system/temp.
     * 
     * @return
     */
    public boolean isTempCollection() {
        return isTempCollection;
    }
    
	/**
	 * Closes the collection, i.e. releases the lock held by 
	 * the current thread. This is a shortcut for getLock().release().
	 */
	public void release() {
//		LOG.debug("releasing lock on " + name);
		getLock().release();
	}
	
	/**
	 * Update the specified child-collection.
	 * 
	 * @param child
	 */
	public void update(Collection child) {
        //TODO : use dedicated function in XmldbURI
		final int p = child.name.lastIndexOf("/") + 1;
		final String childName = child.name.substring(p);
		subcollections.remove(childName);
		subcollections.add(childName);
	}

	/**
	 *  Add a document to the collection.
	 *
	 *@param  doc 
	 */
	public void addDocument(Txn transaction, DBBroker broker, DocumentImpl doc) {
		if (doc.getDocId() == DocumentImpl.UNKNOWN_DOCUMENT_ID)
			doc.setDocId(broker.getNextDocumentId(transaction, this));
		documents.put(doc.getFileName(), doc);
	}
	
	/**
	 * Removes the document from the internal list of resources, but
	 * doesn't delete the document object itself.
	 * 
	 * @param doc
	 */
	public void unlinkDocument(DocumentImpl doc) {
	    documents.remove(doc.getFileName());
	}
	
	/**
	 *  Return an iterator over all subcollections.
	 * 
	 * The list of subcollections is copied first, so modifications
	 * via the iterator have no affect.
	 *
	 *@return    Description of the Return Value
	 */
	public Iterator collectionIterator() {
		try {
			getLock().acquire(Lock.READ_LOCK);
			return subcollections.stableIterator();
		} catch (LockException e) {
			LOG.warn(e.getMessage(), e);
			return null;
		} finally {
			getLock().release();
		}
	}

	/**
	 * Load all collections below this collections
	 * and return them in a List.
	 * 
	 * @return List
	 */
	public List getDescendants(DBBroker broker, User user) {
		final ArrayList cl = new ArrayList(subcollections.size());
		try {
			getLock().acquire(Lock.READ_LOCK);
			Collection child;
			String childName;
			for (Iterator i = subcollections.iterator(); i.hasNext(); ) {
				childName = (String) i.next();
                //TODO : use dedicated function in XmldbURI
				child = broker.getCollection(name + "/" + childName);
				if (permissions.validate(user, Permission.READ)) {
					cl.add(child);
					if (child.getChildCollectionCount() > 0)
						cl.addAll(child.getDescendants(broker, user));
				}
			}
		} catch (LockException e) {
			LOG.warn(e.getMessage(), e);
		} finally {
			getLock().release();
		}
		return cl;
	}

	/**
	 * Retrieve all documents contained in this collections.
	 * 
	 * If recursive is true, documents from sub-collections are
	 * included.
	 * 
	 * @param user
	 * @param recursive
	 * @return
	 */
	public DocumentSet allDocs(DBBroker broker, DocumentSet docs,
			boolean recursive, boolean checkPermissions) {
		if (permissions.validate(broker.getUser(), Permission.READ)) {
			CollectionCache cache = broker.getBrokerPool().getCollectionsCache();
			synchronized (cache) {
				getDocuments(broker, docs, checkPermissions);
				if (recursive)
					allDocs(broker, docs, checkPermissions);
			}
		}
		return docs;
	}

	private DocumentSet allDocs(DBBroker broker, DocumentSet docs, boolean checkPermissions) {
        try {
			getLock().acquire(Lock.READ_LOCK);
			Collection child;
			String childName;
            Iterator i = subcollections.iterator();
			while (i.hasNext() ) {
				childName = (String) i.next();
                //TODO : use dedicated function in XmldbURI
				child = broker.getCollection(name + "/" + childName);
				if(child == null) {
					LOG.warn("child collection " + childName + " not found. Skipping ...");
					// we always check if we have permissions to read the child collection
				} else if (child.permissions.validate(broker.getUser(), Permission.READ)) {
					child.getDocuments(broker, docs, checkPermissions);
                    if (child.getChildCollectionCount() > 0)
						child.allDocs(broker, docs, checkPermissions);
				}
			}
		} catch (LockException e) {
			LOG.warn(e.getMessage(), e);
		} finally {
            getLock().release();
		}
		return docs;
	}

	/**
	 * Add all documents to the specified document set.
	 *  
	 * @param docs
	 */
	public DocumentSet getDocuments(DBBroker broker, DocumentSet docs, boolean checkPermissions) {
		try {
			getLock().acquire(Lock.READ_LOCK);
			docs.addCollection(this);
			docs.addAll(broker, documents.values(), checkPermissions);
		} catch (LockException e) {
			LOG.warn(e.getMessage(), e);
		} finally {
			getLock().release();
		}
		return docs;
	}

	/**
	 * Check if this collection may be safely removed from the
	 * cache. Returns false if there are ongoing write operations,
	 * i.e. one or more of the documents is locked for
	 * write.
	 * 
	 * @return
	 */
	public boolean allowUnload() {
		for (Iterator i = documents.values().iterator(); i.hasNext(); ) {
			DocumentImpl doc = (DocumentImpl) i.next();
			if (doc.isLockedForWrite())
				return false;
		}
		return true;
//		try {
//			lock.acquire(Lock.WRITE_LOCK);
//			for (Iterator i = documents.values().iterator(); i.hasNext(); ) {
//				DocumentImpl doc = (DocumentImpl) i.next();
//				if (doc.isLockedForWrite())
//					return false;
//			}
//			return true;
//		} catch (LockException e) {
//			LOG.warn("Failed to acquire lock on collection: " + getName(), e);
//		} finally {
//			lock.release();
//		}
//		return false;
	}
	
	public int compareTo(Object obj) {
		Collection other = (Collection) obj;
		if (collectionId == other.collectionId)
			return Constants.EQUAL;
		else if (collectionId < other.collectionId)
			return Constants.INFERIOR;
		else
			return Constants.SUPERIOR;
	}

	public boolean equals(Object obj) {
		if (!(obj instanceof Collection))
			return false;
		return ((Collection) obj).collectionId == collectionId;
	}

	/**
	 *  Return the number of child-collections managed by this
	 * collection.
	 *
	 *@return    The childCollectionCount value
	 */
	public int getChildCollectionCount() {
		try {
			getLock().acquire(Lock.READ_LOCK);
			return subcollections.size();
		} catch (LockException e) {
			LOG.warn(e.getMessage(), e);
			return 0;
		} finally {
			getLock().release();
		}
	}

	/**
	 *  Get a child resource as identified by path. This method doesn't put
	 * a lock on the document nor does it recognize locks held by other threads.
	 * There's no guarantee that the document still exists when accessing it.
	 * 
	 *@param  name  The name of the document (without collection path)
	 *@return   the document
	 */
	public DocumentImpl getDocument(DBBroker broker, String name) {
		try {
			getLock().acquire(Lock.READ_LOCK);
			DocumentImpl doc = (DocumentImpl) documents.get(name);
			if(doc == null)
			    LOG.debug("Document " + name + " not found!");
			return doc;
		} catch (LockException e) {
			LOG.warn(e.getMessage(), e);
			return null;
		} finally {
			getLock().release();
		}
	}
	
	/**
	 * Retrieve a child resource after putting a read lock on it. With this method,
	 * access to the received document object is safe. 
	 * 
	 * @param broker
	 * @param name
	 * @return
	 * @throws LockException
	 */
	public DocumentImpl getDocumentWithLock(DBBroker broker, String name) throws LockException {
	    try {
	        getLock().acquire(Lock.READ_LOCK);
	        DocumentImpl doc = (DocumentImpl) documents.get(name);
	        if(doc == null)
	        	return null;
	        Lock updateLock = doc.getUpdateLock();
	        updateLock.acquire(Lock.READ_LOCK);
	        return doc;
        } finally {
	        getLock().release();
	    }
	}

	/**
	 * Retrieve a child resource after putting a read lock on it. With this method,
	 * access to the received document object is safe. 
	 * 
	 * @param broker
	 * @param name
	 * @return
	 * @throws LockException
	 */
	public DocumentImpl getDocumentWithLock(DBBroker broker, String name, int lockMode) 
	throws LockException {
	    try {
	        getLock().acquire(Lock.READ_LOCK);
	        DocumentImpl doc = (DocumentImpl) documents.get(name);
	        if(doc == null)
	        	return null;
	        Lock updateLock = doc.getUpdateLock();
	        updateLock.acquire(lockMode);
	        return doc;
        } finally {
	        getLock().release();
	    }
	}
	
	/**
	 * Release any locks held on the document.
	 * 
	 * @param doc
	 */
	public void releaseDocument(DocumentImpl doc) {
	    if(doc != null) {
	        doc.getUpdateLock().release(Lock.READ_LOCK);
	    }
	}
	
	/**
	 *  Returns the number of documents in this collection.
	 *
	 *@return    The documentCount value
	 */
	public int getDocumentCount() {
		try {
			getLock().acquire(Lock.READ_LOCK);
			return documents.size();
		} catch (LockException e) {
			LOG.warn(e.getMessage(), e);
			return 0;
		} finally {
			getLock().release();
		}
	}
	
	/**
	 *  Get the internal id.
	 *
	 *@return    The id value
	 */
	public short getId() {
		return collectionId;
	}

	/**
	 *  Get the name of this collection.
	 *
	 *@return    The name value
	 */
	public String getName() {
		return name;
	}

	/**
	 *  Returns the parent-collection.
	 *
	 *@return    The parent-collection or null if this
	 *is the root collection.
	 */
	public String getParentPath() {
        ///TODO : use dedicated function in XmldbURI
		if (name.equals(DBBroker.ROOT_COLLECTION))
			return null;
		String parent = (name.lastIndexOf("/") < 1 ? DBBroker.ROOT_COLLECTION : name.substring(0,
				name.lastIndexOf("/")));
		return parent;
	}

	/**
	 *  Gets the permissions attribute of the Collection object
	 *
	 *@return    The permissions value
	 */
	public Permission getPermissions() {
		try {
			getLock().acquire(Lock.READ_LOCK);
			return permissions;
		} catch (LockException e) {
			LOG.warn(e.getMessage(), e);
			return permissions;
		} finally {
			getLock().release();
		}
	}

	/**
	 *  Check if the collection has a child document.
	 *
	 *@param  name  the name (without path) of the document
	 *@return  
	 */
	public boolean hasDocument(String name) {
		return documents.containsKey(name);
	}

	/**
	 *  Check if the collection has a sub-collection.
	 *
	 *@param  name  the name of the subcollection (without path).
	 *@return  
	 */
	public boolean hasSubcollection(String name) {
		try {
			getLock().acquire(Lock.READ_LOCK);
			return subcollections.contains(name);
		} catch (LockException e) {
			LOG.warn(e.getMessage(), e);
            //TODO : ouch ! -pb
			return subcollections.contains(name);
		} finally {
			getLock().release();
		}
	}

	/**
	 *  Returns an iterator on the child-documents in this collection.
	 *
	 *@return
	 */
	public Iterator iterator(DBBroker broker) {
		return getDocuments(broker, new DocumentSet(), false).iterator();
	}
	
	/**
	 * Read collection contents from the stream.
	 * 
	 * @param istream
	 * @throws IOException
	 */
	public void read(DBBroker broker, VariableByteInput istream) throws IOException {
		collectionId = istream.readShort();
		final int collLen = istream.readInt();
		subcollections = new ObjectHashSet(collLen);
		for (int i = 0; i < collLen; i++)
			subcollections.add(istream.readUTF());
		final int uid = istream.readInt();
		final int gid = istream.readInt();        
		final int perm = istream.readInt();
        created = istream.readLong();
        
        final SecurityManager secman = broker.getBrokerPool().getSecurityManager();
		if (secman == null) {
            //TODO : load default permissions ? -pb
			permissions.setOwner(SecurityManager.DBA_USER);
			permissions.setGroup(SecurityManager.DBA_GROUP);
		} else {
			permissions.setOwner(secman.getUser(uid));
            Group group = secman.getGroup(gid);
            if (group != null)
                permissions.setGroup(group.getName());
		}
        ///TODO : why this mask ? -pb
		permissions.setPermissions(perm & 0777);		
		broker.readDocuments(this);
	}

	/**
	 *  Remove the specified sub-collection.
	 *
	 *@param  name  Description of the Parameter
	 */
	public void removeCollection(String name) throws LockException {
		try {
			getLock().acquire(Lock.WRITE_LOCK);
			subcollections.remove(name);
		} finally {
			getLock().release();
		}
	}
	
	/**
	 *  Remove the specified document from the collection.
	 *
	 *@param  name
	 */
	public void removeDocument(Txn transaction, DBBroker broker, String docname)
			throws PermissionDeniedException, TriggerException, LockException {
        try {
            getLock().acquire(Lock.READ_LOCK);
            
    	    DocumentImpl doc = getDocument(broker, docname);
    	    if (doc == null)
    	        return;
    	    if(doc.isLockedForWrite())
    	        throw new PermissionDeniedException("Document " + doc.getFileName() + 
    	        " is locked for write");
    	    if (!getPermissions().validate(broker.getUser(), Permission.WRITE))
    	        throw new PermissionDeniedException(
    	                "Write access to collection denied; user=" + broker.getUser().getName());
    	    if (!doc.getPermissions().validate(broker.getUser(), Permission.WRITE))
    	        throw new PermissionDeniedException("Permission to remove document denied");
            
            DocumentTrigger trigger = null;
            if (!CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE.equals(docname)) {
                if (triggersEnabled) {
                    CollectionConfiguration config = getConfiguration(broker);
                    if (config != null)
                        trigger = (DocumentTrigger) config.getTrigger(Trigger.REMOVE_DOCUMENT_EVENT);
                }
            } else {
                // we remove a collection.xconf configuration file: tell the configuration manager to
                // reload the configuration.
                CollectionConfigurationManager confMgr = broker.getBrokerPool().getConfigurationManager();
                confMgr.invalidateAll(getName());
            }
            
    	    if (trigger != null) {
    	        trigger.prepare(Trigger.REMOVE_DOCUMENT_EVENT, broker, transaction, 
    	        		getName() + "/" + docname, doc);
    	    }
    	    
    	    broker.removeDocument(transaction, doc);
    	    documents.remove(docname);
            
    	    if (trigger != null) {
    	        trigger.finish(Trigger.REMOVE_DOCUMENT_EVENT, broker, transaction, doc);
    	    }
            
    	    broker.getBrokerPool().getNotificationService().notifyUpdate(doc, UpdateListener.REMOVE);
            
        } finally {
            getLock().release();
        }
	}

	public void removeBinaryResource(Txn transaction, DBBroker broker, String docName) 
        throws PermissionDeniedException, LockException, TriggerException {
        
        try {
            getLock().acquire(Lock.WRITE_LOCK);
    	    DocumentImpl doc = getDocument(broker, docName);
    	    if(doc.isLockedForWrite())
    	        throw new PermissionDeniedException("Document " + doc.getFileName() + 
    	        " is locked for write");
    	    if (!getPermissions().validate(broker.getUser(), Permission.WRITE))
    	        throw new PermissionDeniedException(
    	                "write access to collection denied; user=" + broker.getUser().getName());
    	    if (!doc.getPermissions().validate(broker.getUser(), Permission.WRITE))
    	        throw new PermissionDeniedException("permission to remove document denied");
   	    
    	    removeBinaryResource(transaction, broker, doc);
        } finally {
            getLock().release();
        }
	}

	public void removeBinaryResource(Txn transaction, DBBroker broker, DocumentImpl doc) 
            throws PermissionDeniedException, LockException, TriggerException {
		
        if (doc == null)
			return;
        
        try {
            getLock().acquire(Lock.WRITE_LOCK);
            if (doc.getResourceType() != DocumentImpl.BINARY_FILE)
                throw new PermissionDeniedException("document " + doc.getFileName()
                        + " is not a binary object");
            if(doc.isLockedForWrite())
                throw new PermissionDeniedException("Document " + doc.getFileName() + 
                " is locked for write");
            if (!getPermissions().validate(broker.getUser(), Permission.WRITE))
                throw new PermissionDeniedException(
                        "write access to collection denied; user=" + broker.getUser().getName());
            if (!doc.getPermissions().validate(broker.getUser(), Permission.WRITE))
                throw new PermissionDeniedException("permission to remove document denied");
            
            DocumentTrigger trigger = null;
            if (triggersEnabled) {
            	CollectionConfiguration config = getConfiguration(broker);
	            if (config != null) {
	                trigger = (DocumentTrigger) config.getTrigger(Trigger.REMOVE_DOCUMENT_EVENT);	                
	            }
            }
            
            if (trigger != null)
                trigger.prepare(Trigger.REMOVE_DOCUMENT_EVENT, broker, transaction, doc.getName(), doc);
            
            broker.removeBinaryResource(transaction, (BinaryDocument) doc);
            documents.remove(doc.getFileName());
            
            if (trigger != null) {
            	trigger.finish(Trigger.REMOVE_DOCUMENT_EVENT, broker, transaction, null);
            }
        } finally {
            getLock().release();
        }
	}
	
	public void store(Txn transaction, DBBroker broker, final IndexInfo info, final InputSource source, boolean privileged)
			throws EXistException, PermissionDeniedException, TriggerException, SAXException, LockException {
		storeInternal(transaction, broker, info, privileged, new StoreBlock() {
			public void run() throws EXistException, SAXException {
				try {
					final InputStream is = source.getByteStream();
					if (is != null)
						is.reset();
					else {
						final Reader cs = source.getCharacterStream();
						if (cs != null)
							cs.reset();
					}
				} catch (IOException e) {
					LOG.debug("could not reset input source", e);
				}
				try {
					info.getReader().parse(source);
				} catch (IOException e) {
					throw new EXistException(e);
				}
			}
		});
	}
    
	public void store(Txn transaction, DBBroker broker, final IndexInfo info, final String data, boolean privileged)
			throws EXistException, PermissionDeniedException, TriggerException, SAXException, LockException {
		storeInternal(transaction, broker, info, privileged, new StoreBlock() {
			public void run() throws SAXException, EXistException {
				try {
					info.getReader().parse(new InputSource(new StringReader(data)));
				} catch (IOException e) {
					throw new EXistException(e);
				}
			}
		});
	}
    
    public void store(Txn transaction, DBBroker broker, final IndexInfo info, final Node node, boolean privileged)
            throws EXistException, PermissionDeniedException, TriggerException, SAXException, LockException {
        storeInternal(transaction, broker, info, privileged, new StoreBlock() {
            public void run() throws EXistException, SAXException {
                info.getDOMStreamer().serialize(node, true);
            }
        });
    }    
	
	private interface StoreBlock {
		public void run() throws EXistException, SAXException;
	}
	
	private void storeInternal(Txn transaction, DBBroker broker, IndexInfo info, boolean privileged, StoreBlock doParse) throws EXistException, SAXException {
		DocumentImpl document = info.getIndexer().getDocument();
		LOG.debug("storing document " + document.getDocId() + " ...");
		try {
			doParse.run();
            
			broker.storeDocument(transaction, document);
			broker.closeDocument();
			broker.flush();
//			broker.checkTree(document);
			LOG.debug("document stored.");
			// if we are running in privileged mode (e.g. backup/restore), notify the SecurityManager about changes
			if (getName().equals(DBBroker.SYSTEM_COLLECTION) && document.getFileName().equals(SecurityManager.ACL_FILE)
			        && privileged == false) {
			    // inform the security manager that system data has changed
			    LOG.debug("users.xml changed");
			    broker.getBrokerPool().reloadSecurityManager(broker);
			}
		} finally {
			document.getUpdateLock().release(Lock.WRITE_LOCK);
			releaseReader(broker, info.getReader());
		}
        
      collectionConfEnabled = true;
		broker.deleteObservers();
		info.finishTrigger(broker, transaction, document);
		broker.getBrokerPool().getNotificationService().notifyUpdate(document, 
				(info.getEvent() == Trigger.UPDATE_DOCUMENT_EVENT ? UpdateListener.UPDATE : UpdateListener.ADD));
	}
    
    public IndexInfo validate(Txn transaction, DBBroker broker, String docName, String data)
            throws EXistException, PermissionDeniedException, TriggerException, 
            SAXException, LockException {
        return validate(transaction, broker, docName, new InputSource(new StringReader(data)));
    }    

    public IndexInfo validate(Txn transaction, final DBBroker broker, String docName, final InputSource source) 
            throws EXistException, PermissionDeniedException, TriggerException, 
            SAXException, LockException {
        return validateInternal(transaction, broker, docName, new ValidateBlock() {
            public void run(IndexInfo info) throws SAXException, EXistException {
                info.setReader(getReader(broker), Collection.this);
                try {
                    info.getReader().parse(source);
                } catch (IOException e) {
                    throw new EXistException(e);
                }
            }
        });
    }    
    
    public IndexInfo validate(Txn transaction, final DBBroker broker, String docName, final Node node)
            throws EXistException, PermissionDeniedException, TriggerException, 
            SAXException, LockException {
        return validateInternal(transaction, broker, docName, new ValidateBlock() {
            public void run(IndexInfo info) throws SAXException {
                info.setDOMStreamer(new DOMStreamer());
                info.getDOMStreamer().serialize(node, true);
            }
        });
    }
    
    private interface ValidateBlock {
        public void run(IndexInfo info) throws SAXException, EXistException;
    }
	
	private IndexInfo validateInternal(Txn transaction, DBBroker broker, String docName, ValidateBlock doValidate) 
			throws EXistException, PermissionDeniedException, TriggerException, SAXException, LockException {
		if (broker.isReadOnly()) throw new PermissionDeniedException("Database is read-only");
		DocumentImpl document, oldDoc = null;
		boolean oldDocLocked = false;
		try {
			getLock().acquire(Lock.WRITE_LOCK);
			oldDoc = (DocumentImpl) documents.get(docName);
			document = new DocumentImpl(broker, docName,	this);
		
			if (oldDoc == null) {
				CollectionConfiguration config = getConfiguration(broker);
				if (config != null) {
					document.setPermissions(config.getDefResPermissions());
				}
			} else
				document.setPermissions(oldDoc.getPermissions().getPermissions());
			
			checkPermissions(transaction, broker, docName, oldDoc);
			manageDocumentInformation(broker, docName, oldDoc, document );
			
			Indexer indexer = new Indexer(broker, transaction);
			IndexInfo info = new IndexInfo(indexer);
			indexer.setDocument(document);
			addObserversToIndexer(broker, indexer);
			indexer.setValidating(true);
			
			// if !triggersEnabled, setupTriggers will return null anyway, so no need to check
			info.setTrigger(
					setupTriggers(broker, docName, oldDoc != null),
					oldDoc == null ? Trigger.STORE_DOCUMENT_EVENT : Trigger.UPDATE_DOCUMENT_EVENT);

            //TODO : use dedicated function in XmldbURI
			info.prepareTrigger(broker, transaction, getName() + "/" + docName, oldDoc);

			LOG.debug("Scanning document " + getName() + "/" + docName);
			doValidate.run(info);
			
			document.setMaxDepth(document.getMaxDepth() + 1);
			document.calculateTreeLevelStartPoints();
			// new document is valid: remove old document 
			if (oldDoc != null) {
				LOG.debug("removing old document " + oldDoc.getFileName());
				oldDoc.getUpdateLock().acquire(Lock.WRITE_LOCK);
				oldDocLocked = true;
				if (oldDoc.getResourceType() == DocumentImpl.BINARY_FILE)
					broker.removeBinaryResource(transaction, (BinaryDocument) oldDoc);
				else
					broker.removeDocument(transaction, oldDoc, false);
				oldDoc.copyOf(document);
				indexer.setDocumentObject(oldDoc);
				oldDocLocked = false;		// old has become new at this point
				document = oldDoc;
			} else {
			    document.getUpdateLock().acquire(Lock.WRITE_LOCK);
			    document.setDocId(broker.getNextDocumentId(transaction, this));
			    addDocument(transaction, broker, document);
			}

			indexer.setValidating(false);
			info.postValidateTrigger();
			return info;
		} finally {
		   if (oldDocLocked) oldDoc.getUpdateLock().release(Lock.WRITE_LOCK);
			getLock().release();
		}
	}

	/** add observers to the indexer
	 * @param broker
	 * @param indexer
	 */
	private void addObserversToIndexer(DBBroker broker, Indexer indexer) {
		broker.deleteObservers();
		if (observers != null) {
            for (int i = 0; i < observers.length; i++) {
				indexer.addObserver(observers[i]);
				broker.addObserver(observers[i]);
			}
		}
	}

	/** If an old document exists, keep information  about  the document.
	 * @param broker
	 * @param name
	 * @param oldDoc
	 * @param document
	 */
	private void manageDocumentInformation(DBBroker broker, String name, DocumentImpl oldDoc,
			DocumentImpl document ) {
		if (oldDoc != null) {
			document.setCreated(oldDoc.getCreated());
			document.setLastModified(System.currentTimeMillis());
			document.setPermissions(oldDoc.getPermissions());
		} else {
			document.setCreated(System.currentTimeMillis());
			document.getPermissions().setOwner(broker.getUser());
			document.getPermissions().setGroup(
					broker.getUser().getPrimaryGroup());
		}
	}

	/** 
	 * Check Permissions about user and document, and throw exceptions if necessary.
	 * 
	 * @param broker
	 * @param name
	 * @param oldDoc old Document existing in database prior to adding a new one with same name.
	 * @throws LockException
	 * @throws PermissionDeniedException
	 */
	private void checkPermissions(Txn transaction, DBBroker broker, String name, DocumentImpl oldDoc) throws LockException, PermissionDeniedException {
		if (oldDoc != null) {

			LOG.debug("Found old doc " + oldDoc.getDocId());
			// check if the document is locked by another user
			User lockUser = oldDoc.getUserLock();
			if(lockUser != null && !lockUser.equals(broker.getUser()))
				throw new PermissionDeniedException("The document is locked by user " +
						lockUser.getName());
            

			// do we have permissions for update?
			if (!oldDoc.getPermissions().validate(broker.getUser(),
					Permission.UPDATE))
				throw new PermissionDeniedException(
						"Document exists and update is not allowed");
			if (!(getPermissions().validate(broker.getUser(), Permission.UPDATE) ||
			        getPermissions().validate(broker.getUser(), Permission.WRITE)))
				throw new PermissionDeniedException(
						"Document exists and update is not allowed for the collection");
			// do we have write permissions?
		} else if (!getPermissions().validate(broker.getUser(),
				Permission.WRITE))
			throw new PermissionDeniedException(
					"User '" + broker.getUser().getName() + "' not allowed to write to collection '" + getName() + "'");
	}

	private DocumentTrigger setupTriggers(DBBroker broker, String docName, boolean update) {
		
        //TODO : is this the right place for such a task ? -pb
		if (CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE.equals(docName)) {
		    // we are updating collection.xconf. Notify configuration manager
			CollectionConfigurationManager confMgr = broker.getBrokerPool().getConfigurationManager();
			confMgr.invalidateAll(getName());
			collectionConfEnabled = false;
            return null;
		} 
		if (!triggersEnabled)
		    return null;
        
		CollectionConfiguration config = getConfiguration(broker);
		if (config == null)
          return null;
                
        DocumentTrigger trigger;
        if (update) 
            trigger = (DocumentTrigger) config.getTrigger(Trigger.UPDATE_DOCUMENT_EVENT);
        else
            trigger = (DocumentTrigger) config.getTrigger(Trigger.STORE_DOCUMENT_EVENT);  
       
        if(trigger == null) 
            return null;
        
        if (update) 
            LOG.debug("Using update trigger '" + trigger.getClass().getName() + "'");
        else
            LOG.debug("Using store trigger '" + trigger.getClass().getName() + "'");

		return trigger;
	}

	public BinaryDocument addBinaryResource(Txn transaction, DBBroker broker,
			String name, byte[] data, String mimeType) 
            throws EXistException, PermissionDeniedException, LockException, TriggerException {
		return addBinaryResource(transaction, broker, name, data, mimeType, null, null);
	}

	public BinaryDocument addBinaryResource(Txn transaction, DBBroker broker,
			String name, byte[] data, String mimeType, Date created, Date modified) 
            throws EXistException, PermissionDeniedException, LockException, TriggerException {
		if (broker.isReadOnly())
			throw new PermissionDeniedException("Database is read-only");
		BinaryDocument blob = null;
		DocumentImpl oldDoc = getDocument(broker, name);
		blob = new BinaryDocument(broker, name, this);
        blob.setMimeType(mimeType);
		try {
			getLock().acquire(Lock.WRITE_LOCK);
			checkPermissions(transaction, broker, name, oldDoc);
			DocumentTrigger trigger = null;
			int event = 0;
            if (triggersEnabled) {
            	CollectionConfiguration config = getConfiguration(broker);
	            if (config != null) {
	            	event = oldDoc != null ? Trigger.UPDATE_DOCUMENT_EVENT : Trigger.STORE_DOCUMENT_EVENT;
	                trigger = (DocumentTrigger) config.getTrigger(event);
	                if (trigger != null) {
	                	trigger.prepare(event, broker, transaction, blob.getName(), blob);
	                }
	            }
            }
            
			manageDocumentInformation(broker, name, oldDoc, blob );
            
			if (oldDoc != null) {
			    LOG.debug("removing old document " + oldDoc.getFileName());
			    if (oldDoc instanceof BinaryDocument)
			        broker.removeBinaryResource(transaction, (BinaryDocument) oldDoc);
			    else
			        broker.removeDocument(transaction, oldDoc);
			}

			if(created != null)
				blob.setCreated(created.getTime());
			
			if(modified != null)
				blob.setLastModified(modified.getTime());

            broker.storeBinaryResource(transaction, blob, data);
            addDocument(transaction, broker, blob);
            broker.storeDocument(transaction, blob);
            
			broker.closeDocument();
			
			if (trigger != null) {
				trigger.finish(event, broker, transaction, blob);
			}
			return blob;
		} finally {
			getLock().release();
		}
	}

	
	public void setId(short id) {
		this.collectionId = id;
	}

	public void setPermissions(int mode) throws LockException {
		try {
			getLock().acquire(Lock.WRITE_LOCK);
			permissions.setPermissions(mode);
		} finally {
			getLock().release();
		}
	}

	public void setPermissions(String mode) throws SyntaxException, LockException {
		try {
			getLock().acquire(Lock.WRITE_LOCK);
			permissions.setPermissions(mode);
		} finally {
			getLock().release();
		}
	}

	/**
	 * Set permissions for the collection.
	 * 
	 * @param permissions
	 */
	public void setPermissions(Permission permissions) throws LockException {
		try {
			getLock().acquire(Lock.WRITE_LOCK);
			this.permissions = permissions;
		} finally {
			getLock().release();
		}
	}

	/**
	 * Write collection contents to stream.
	 * 
	 * @param ostream
	 * @throws IOException
	 */
	public void write(DBBroker broker, VariableByteOutputStream ostream) throws IOException {
		ostream.writeShort(collectionId);
		ostream.writeInt(subcollections.size());
		String childColl;
		for (Iterator i = subcollections.iterator(); i.hasNext(); ) {
			childColl = (String) i.next();
			ostream.writeUTF(childColl);
		}
		org.exist.security.SecurityManager secman = broker.getBrokerPool()
				.getSecurityManager();
		if (secman == null) {
			ostream.writeInt(1);
			ostream.writeInt(1);
		} else {
			User user = secman.getUser(permissions.getOwner());
			Group group = secman.getGroup(permissions.getOwnerGroup());
			ostream.writeInt(user.getUID());
			ostream.writeInt(group.getId());
		}
		ostream.writeInt(permissions.getPermissions());
		ostream.writeLong(created);
		DocumentImpl doc;
		for (Iterator i = documents.values().iterator(); i.hasNext(); ) {
			doc = (DocumentImpl) i.next();
			doc.write(ostream);
		}
	}

	private CollectionConfiguration getConfiguration(DBBroker broker) {
	    if (!collectionConfEnabled)
	        return null;
	    if (configuration != null)
	    	return configuration;
        //System collection has no configuration
		if (name.equals(DBBroker.SYSTEM_COLLECTION))
            return null;
        
	    CollectionConfigurationManager manager = broker.getBrokerPool().getConfigurationManager();
	    if (manager == null)
            return null;
        
        //Attempt to get configuration
        collectionConfEnabled = false;
	    try {
            configuration = manager.getConfiguration(broker, this);            
        } catch (CollectionConfigurationException e) {
            LOG.warn("Failed to load collection configuration for '" + getName() + "'", e);
        }
        //TODO : we should not consider the collectiona configured after a failure ! -pb  
        collectionConfEnabled = true;
	   
        return configuration;	
	}

	/**
	 * Should the collection configuration document be enabled
	 * for this collection? Called by {@link org.exist.storage.NativeBroker}
	 * before doing a reindex.
	 * 
	 * @param enabled
	 */
	public void setConfigEnabled(boolean enabled) {
	    collectionConfEnabled = enabled;
	}
	
	public void invalidateConfiguration() {
		configuration = null;
	}
	
	/**
	 * Set the internal storage address of the collection data.
	 * 
	 * @param addr
	 */
	public void setAddress(long addr) {
		this.address = addr;
	}

	public long getAddress() {
		return this.address;
	}
	
	public void setCreationTime(long ms) {
		created = ms;
	}

	public long getCreationTime() {
		return created;
	}

	public void setTriggersEnabled(boolean enabled) {
		try {
			getLock().acquire(Lock.WRITE_LOCK);
			this.triggersEnabled = enabled;
		} catch (LockException e) {
			LOG.warn(e.getMessage(), e);
            //Ouch ! -pb
			this.triggersEnabled = enabled;
		} finally {
			getLock().release();
		}
	}


    /** set user-defined Reader */
	public void setReader(XMLReader reader){
			userReader = reader;
	}
	
	/** If user-defined Reader is set, return it; otherwise return JAXP default XMLReader 
	 * configured by eXist. */
	private XMLReader getReader(DBBroker broker) throws EXistException,
			SAXException {
		
		if ( userReader != null )
			return userReader;
		
		Configuration config = broker.getConfiguration();
		resolver = (CatalogResolver) config.getProperty("resolver");
		return broker.getBrokerPool().getParserPool().borrowXMLReader();
	}

	private void releaseReader(DBBroker broker, XMLReader reader) {
		if (userReader != null )
			return;
		broker.getBrokerPool().getParserPool().returnXMLReader(reader);
	}
	
	/**
	 * Try to resolve external entities.
	 * 
	 * This method forwards the request to the resolver. If that fails,
	 * the method replaces absolute file names with relative ones 
	 * and retries to resolve. This makes it possible to use relative
	 * file names in the catalog.
	 * 
	 * @see org.xml.sax.EntityResolver#resolveEntity(java.lang.String, java.lang.String)
	 */
	public InputSource resolveEntity(String publicId, String systemId)
			throws SAXException, IOException {
		InputSource is = resolver.resolveEntity(publicId, systemId);
		// if resolution failed and publicId == null,
		// try to make absolute file names relative and retry
		if (is == null) {
			if (publicId != null)
				return null;
			URL url = new URL(systemId);
			if (url.getProtocol().equals("file")) {
				String path = url.getPath();
				File f = new File(path);
				if (!f.canRead())
					return resolver.resolveEntity(null, f.getName());
				else
					return new InputSource(f.getAbsolutePath());
			} else
				return new InputSource(url.openStream());
		}
		return is;
	}

	private void setFeature(SAXParserFactory factory, String feature,
			boolean value) {
		try {
			factory.setFeature(feature, value);
		} catch (SAXNotRecognizedException e) {
			LOG.warn(e);
		} catch (SAXNotSupportedException snse) {
			LOG.warn(snse);
		} catch (ParserConfigurationException pce) {
			LOG.warn(pce);
		}
	}

	/* (non-Javadoc)
	 * @see java.util.Observable#addObserver(java.util.Observer)
	 */
	public void addObserver(Observer o) {
        if (hasObserver(o)) return;
		if (observers == null) {
			observers = new Observer[1];
            observers[0] = o;
        } else {
             Observer n[] = new Observer[observers.length + 1];
             System.arraycopy(observers, 0, n, 0, observers.length);
             n[observers.length] = o;
             observers = n;
        }
	}

    private boolean hasObserver(Observer o) {
        if (observers == null)
            return false;
        for (int i = 0; i < observers.length; i++) {
            if (observers[i] == o)
                return true;
        }
        return false;
    }
    
	/* (non-Javadoc)
	 * @see java.util.Observable#deleteObservers()
	 */
	public void deleteObservers() {
		if (observers != null)
			observers = null;
	}

	/* (non-Javadoc)
	 * @see org.exist.storage.cache.Cacheable#getKey()
	 */
	public long getKey() {
		return collectionId;
	}

	/* (non-Javadoc)
	 * @see org.exist.storage.cache.Cacheable#getReferenceCount()
	 */
	public int getReferenceCount() {
		return refCount;
	}

	/* (non-Javadoc)
	 * @see org.exist.storage.cache.Cacheable#incReferenceCount()
	 */
	public int incReferenceCount() {
		return ++refCount;
	}

	/* (non-Javadoc)
	 * @see org.exist.storage.cache.Cacheable#decReferenceCount()
	 */
	public int decReferenceCount() {
		return refCount > 0 ? --refCount : 0;
	}

	/* (non-Javadoc)
	 * @see org.exist.storage.cache.Cacheable#setReferenceCount(int)
	 */
	public void setReferenceCount(int count) {
		refCount = count;
	}

	/* (non-Javadoc)
	 * @see org.exist.storage.cache.Cacheable#setTimestamp(int)
	 */
	public void setTimestamp(int timestamp) {
		this.timestamp = timestamp;
	}

	/* (non-Javadoc)
	 * @see org.exist.storage.cache.Cacheable#getTimestamp()
	 */
	public int getTimestamp() {
		return timestamp;
	}

	/* (non-Javadoc)
	 * @see org.exist.storage.cache.Cacheable#release()
	 */
	public boolean sync(boolean syncJournal) {
	    return false;
	}
	
	
    /* (non-Javadoc)
     * @see org.exist.storage.cache.Cacheable#isDirty()
     */
    public boolean isDirty() {
        return false;
    }
    
    public String toString() {
        StringBuffer buf = new StringBuffer();
        buf.append("[");
        for(Iterator i = documents.keySet().iterator(); i.hasNext(); ) {
            buf.append(i.next());
            if(i.hasNext())
                buf.append(", ");
        }
        buf.append("]");
        return buf.toString();
    }

	public IndexSpec getIdxConf(DBBroker broker) {
	    CollectionConfiguration conf = getConfiguration(broker);
	    if(conf == null) {
	        return broker.getIndexConfiguration();
        } else {
	        return conf.getIndexConfiguration();
	    }
	}
}
