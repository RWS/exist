/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2010 The eXist Project
 *  http://exist-db.org
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
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 *  $Id$
 */
package org.exist.security.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.exist.config.ConfigurationException;
import org.exist.config.annotation.ConfigurationClass;
import org.exist.config.annotation.ConfigurationField;
import org.exist.security.Credential;
import org.exist.security.Group;
import org.exist.security.SecurityManager;
import org.exist.security.Account;
import org.exist.security.realm.Realm;
import org.exist.xmldb.XmldbURI;

@ConfigurationClass("account")
public abstract class AbstractAccount extends AbstractPrincipal implements Account {

	@ConfigurationField("home")
	protected XmldbURI home = null;
	
	protected Group defaultRole = null;
	protected Set<Group> roles = null;
	
	//used for internal locking
	private final boolean accountLocked = false;
	
	@ConfigurationField("expired")
	private final boolean accountExpired = false;
	
	@ConfigurationField("credentials-expired")
    private final boolean credentialsExpired = false;
	
	@ConfigurationField("enabled")
    private final boolean enabled = true;
    
	protected Credential _cred = null;

	private Map<String, Object> attributes = new HashMap<String, Object>();

	/**
	 * Indicates if the user belongs to the dba group, i.e. is a superuser.
	 */
	protected boolean hasDbaRole = false;

	public AbstractAccount(AbstractRealm realm, int id, String name) throws ConfigurationException {
		super(realm, realm.collectionAccounts, id, name);
	}
	
	public final Group addGroup(Group group) {
		return addGroup(group.getName());
	}

    /*
	 * (non-Javadoc)
	 * 
	 * @see org.exist.security.User#addGroup(java.lang.String)
	 */
	public final Group addGroup(String name) {
		Group role = realm.getGroup(name);
		if (role == null)
			return null;
		
		if (roles == null) {
			roles = new HashSet<Group>();
		}
		
		roles.add(role);
		
		if (SecurityManager.DBA_GROUP.equals(name))
			hasDbaRole = true;
		
		return role;
	}

	public final void remGroup(String group) {
		for (Group role : roles) {
			if (role.getName().equals(group)) {
				roles.remove(role);
				break;
			}
		}

		if (SecurityManager.DBA_GROUP.equals(group))
			hasDbaRole = false;
	}

	public final void setGroups(String[] groups) {
//		this.groups = groups;
//		for (int i = 0; i < groups.length; i++)
//			if (SecurityManager.DBA_GROUP.equals(groups[i]))
//				hasDbaRole = true;
	}

	public final String[] getGroups() {
		if (roles == null) return new String[0];
		
		int i = 0;
		String[] names = new String[roles.size()];
		for (Group role : roles) {
			names[i] = role.getName();
			i++;
		}
		
		return names;
	}

	public final boolean hasGroup(String group) {
		if (roles == null)
			return false;
		
		for (Group role : roles) {
			if (role.getName().equals(group))
				return true;
		}
		
		return false;
	}

	public final boolean hasDbaRole() {
		return hasDbaRole;
	}

	public final String getPrimaryGroup() {
		if (defaultRole == null) {
			if (roles == null || roles.size() == 0)
				return null;
			
			return ((Group) roles.toArray()[0]).getName();
		}
			
		return defaultRole.getName();
	}

	public final String toString() {
		StringBuffer buf = new StringBuffer();
		buf.append("<account name=\"");
		buf.append(name);
		buf.append("\" ");
		buf.append("id=\"");
		buf.append(Integer.toString(id));
		buf.append("\"");
		if (home != null) {
			buf.append(" home=\"");
			buf.append(home);
			buf.append("\">");
		} else
			buf.append(">");
		if (roles != null) {
			for (Group role : roles) {
				buf.append("<group>");
				buf.append(role.getName());
				buf.append("</group>");
			}
		}
		buf.append("</user>");
		return buf.toString();
	}

	public XmldbURI getHome() {
		return home;
	}

	public boolean equals(Object obj) {
		AbstractAccount other;
		
		if (obj instanceof SubjectImpl) {
			other = ((SubjectImpl) obj).account;
			
		} else if (obj instanceof AbstractAccount) {
			other = (AbstractAccount) obj;
		
		} else {
			return false;
		}
	
		if (other != null)
			return (realm == other.realm && name.equals(other.name)); //id == other.id;

		return false;
	}

	@Override
	public Realm getRealm() {
		return realm;
	}

	/**
	 * Add a named attribute.
	 *
	 * @param name
	 * @param value
	 */
	@Override
	public void setAttribute(String name, Object value) {
		attributes.put(name, value);
	}

	/**
	 * Get the named attribute value.
	 *
	 * @param name The String that is the name of the attribute.
	 * @return The value associated with the name or null if no value is associated with the name.
	 */
	@Override
	public Object getAttribute(String name) {
		return attributes.get(name);
	}

	/**
	 * Returns the set of attributes names.
	 *
	 * @return the Set of attribute names.
	 */
	@Override
	public Set<String> getAttributeNames() {
	    return attributes.keySet();
	}

	@Override
	public Group getDefaultGroup() {
		return defaultRole;
	}

	public void setHome(XmldbURI homeCollection) {
		home = homeCollection;
	}

    public String getUsername() {
    	return getName();
    }

    public boolean isAccountNonExpired() {
    	return !accountExpired;
    }

    public boolean isAccountNonLocked() {
    	return !accountLocked;
    }

    public boolean isCredentialsNonExpired() {
    	return !credentialsExpired;
    }

    public boolean isEnabled() {
    	return enabled;
    }
}