/*
 *  eXist Open Source Native XML Database
 * 
 *  Copyright (C) 2000-03, Wolfgang M. Meier (meier@ifs. tu- darmstadt. de)
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Library General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Library General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 * $Id$
 */
package org.exist.xpath;

import java.util.Iterator;
import java.util.Map;

import org.exist.dom.ContextItem;
import org.exist.dom.DocumentImpl;
import org.exist.dom.DocumentSet;
import org.exist.dom.ExtArrayNodeSet;
import org.exist.dom.NodeProxy;
import org.exist.dom.NodeSet;
import org.exist.storage.IndexPaths;
import org.exist.storage.analysis.SimpleTokenizer;
import org.exist.storage.analysis.TextToken;
import org.exist.util.Configuration;
import org.exist.xpath.functions.*;
import org.exist.xpath.value.AtomicValue;
import org.exist.xpath.value.BooleanValue;
import org.exist.xpath.value.Item;
import org.exist.xpath.value.Sequence;
import org.exist.xpath.value.SequenceIterator;
import org.exist.xpath.value.StringValue;
import org.exist.xpath.value.Type;

/**
 * A general XQuery/XPath2 comparison expression.
 * 
 * @author wolf
 */
public class GeneralComparison extends BinaryOp {

	protected int relation = Constants.EQ;

	public GeneralComparison(StaticContext context, int relation) {
		super(context);
		this.relation = relation;
	}

	public GeneralComparison(
		StaticContext context,
		Expression left,
		Expression right,
		int relation) {
		super(context);
		this.relation = relation;
		// simplify arguments
		if (left instanceof PathExpr && ((PathExpr) left).getLength() == 1)
			add(((PathExpr) left).getExpression(0));
		else
			add(left);
		if (right instanceof PathExpr && ((PathExpr) right).getLength() == 1)
			add(((PathExpr) right).getExpression(0));
		else
			add(right);
	}

	/* (non-Javadoc)
	 * @see org.exist.xpath.BinaryOp#returnsType()
	 */
	public int returnsType() {
		// TODO: Assumes that context sequence is a node set
		if (inPredicate && (getDependencies() & Dependency.CONTEXT_ITEM) == 0) {
			/* If one argument is a node set we directly
			 * return the matching nodes from the context set. This works
			 * only inside predicates.
			 */
			return Type.NODE;
		}
		// In all other cases, we return boolean
		return Type.BOOLEAN;
	}

	/* (non-Javadoc)
	 * @see org.exist.xpath.AbstractExpression#getDependencies()
	 */
	public int getDependencies() {
		int leftDeps = getLeft().getDependencies();
		if (Type.subTypeOf(getLeft().returnsType(), Type.NODE)
			// left expression returns node set
			&& (leftDeps & Dependency.GLOBAL_VARS) == 0
			// and has no dependency on global vars
			&& (leftDeps & Dependency.CONTEXT_ITEM) == 0)
			// and does not depend on the context item
			{
			return Dependency.CONTEXT_SET;
		} else
			return Dependency.CONTEXT_SET + Dependency.CONTEXT_ITEM;
	}

	/* (non-Javadoc)
	 * @see org.exist.xpath.Expression#preselect(org.exist.dom.DocumentSet, org.exist.xpath.StaticContext)
	 */
	public DocumentSet preselect(DocumentSet in_docs) throws XPathException {
		return in_docs;
	}

	/* (non-Javadoc)
	 * @see org.exist.xpath.Expression#eval(org.exist.xpath.StaticContext, org.exist.dom.DocumentSet, org.exist.xpath.value.Sequence, org.exist.xpath.value.Item)
	 */
	public Sequence eval(Sequence contextSequence, Item contextItem)
		throws XPathException {
		/* 
		 * If we are inside a predicate and one of the arguments is a node set, 
		 * we try to speed up the query by returning nodes from the context set.
		 * This works only inside a predicate.
		 */
		if (inPredicate) {
			int rightDeps = getRight().getDependencies();
			if((getDependencies() & Dependency.CONTEXT_ITEM) == 0) {
				if ((rightDeps & Dependency.CONTEXT_ITEM) == 0
					&& (Type.subTypeOf(getRight().returnsType(), Type.STRING)
						|| Type.subTypeOf(getRight().returnsType(), Type.NODE))
					&& (getRight().getCardinality() & Cardinality.MANY) == 0) {
					// lookup search terms in the fulltext index
					return quickNodeSetCompare(contextSequence);
				} else {
					return nodeSetCompare(contextSequence);
				}
			}
		}
		// Fall back to the generic compare process
		return genericCompare(context, contextSequence, contextItem);
	}

	protected BooleanValue genericCompare(
		StaticContext context,
		Sequence contextSequence,
		Item contextItem)
		throws XPathException {
		Sequence ls = getLeft().eval(contextSequence, contextItem);
		Sequence rs = getRight().eval(contextSequence, contextItem);
		AtomicValue lv, rv;
		if (ls.getLength() == 1 && rs.getLength() == 1) {
			lv = ls.itemAt(0).atomize();
			rv = rs.itemAt(0).atomize();
			return new BooleanValue(compareValues(context, lv, rv));
		} else {
			for (SequenceIterator i1 = ls.iterate(); i1.hasNext();) {
				lv = i1.nextItem().atomize();
				if (rs.getLength() == 1
					&& compareValues(context, lv, rs.itemAt(0).atomize()))
					return BooleanValue.TRUE;
				else {
					for (SequenceIterator i2 = rs.iterate(); i2.hasNext();) {
						rv = i2.nextItem().atomize();
						if (compareValues(context, lv, rv))
							return BooleanValue.TRUE;
					}
				}
			}
		}
		return BooleanValue.FALSE;
	}

	/**
	 * Optimized implementation, which can be applied if the left operand
	 * returns a node set. In this case, the left expression is executed first.
	 * All matching context nodes are then passed to the right expression.
	 */
	protected Sequence nodeSetCompare(Sequence contextSequence)
		throws XPathException {
		// evaluate left expression (returning node set)
		NodeSet nodes = (NodeSet) getLeft().eval(contextSequence);
		return nodeSetCompare(nodes, contextSequence);
	}

	protected Sequence nodeSetCompare(
		NodeSet nodes,
		Sequence contextSequence)
		throws XPathException {
		NodeSet result = new ExtArrayNodeSet();
		NodeProxy current;
		ContextItem c;
		Sequence rs;
		AtomicValue lv, rv;
		for (Iterator i = nodes.iterator(); i.hasNext();) {
			current = (NodeProxy) i.next();
			c = current.getContext();
			do {
				lv = current.atomize();
				rs = getRight().eval(c.getNode().toSequence());
				for (SequenceIterator si = rs.iterate(); si.hasNext();) {
					if (compareValues(context, lv, si.nextItem().atomize())) {
						result.add(current);
					}
				}
			} while ((c = c.getNextItem()) != null);
		}
		return result;
	}

	/**
	 * Optimized implementation, which uses the fulltext index to look up
	 * matching string sequences. Applies to comparisons where the left
	 * operand returns a node set and the right operand is a string literal.
	 */
	protected Sequence quickNodeSetCompare(Sequence contextSequence)
		throws XPathException {
		//	evaluate left expression
		NodeSet nodes = (NodeSet) getLeft().eval(contextSequence);
		Sequence rightSeq = getRight().eval(contextSequence);
		if (rightSeq.getLength() > 1)
			// fall back to nodeSetCompare
			return nodeSetCompare(nodes, contextSequence);
		DocumentSet docs = nodes.getDocumentSet();
		String cmp = rightSeq.getStringValue();
		if (getLeft().returnsType() == Type.NODE
			&& relation == Constants.EQ
			&& nodes.hasIndex()
			&& cmp.length() > 0) {
			String cmpCopy = cmp;
			cmp = maskWildcards(cmp);
			// try to use a fulltext search expression to reduce the number
			// of potential nodes to scan through
			SimpleTokenizer tokenizer = new SimpleTokenizer();
			tokenizer.setText(cmp);
			TextToken token;
			String term;
			boolean foundNumeric = false;
			// setup up an &= expression using the fulltext index
			ExtFulltext containsExpr = new ExtFulltext(context, Constants.FULLTEXT_AND);
			int i = 0;
			for (; i < 5 && (token = tokenizer.nextToken(true)) != null; i++) {
				// remember if we find an alphanumeric token
				if (token.getType() == TextToken.ALPHANUM)
					foundNumeric = true;
			}
			// check if all elements are indexed. If not, we can't use the
			// fulltext index.
			if (foundNumeric)
				foundNumeric = checkArgumentTypes(context, docs);
			if ((!foundNumeric) && i > 0) {
				// all elements are indexed: use the fulltext index
				containsExpr.addTerm(new LiteralValue(context, new StringValue(cmp)));
				nodes = (NodeSet) containsExpr.eval(nodes, null);
			}
			cmp = cmpCopy;
		}
		// now compare the input node set to the search expression
		NodeSet r = context.getBroker().getNodesEqualTo(nodes, docs, relation, cmp);
		return r;
	}

	/**
	 * Cast the atomic operands into a comparable type
	 * and compare them.
	 */
	protected boolean compareValues(
		StaticContext context,
		AtomicValue lv,
		AtomicValue rv)
		throws XPathException {
		int ltype = lv.getType();
		int rtype = rv.getType();
		if (ltype == Type.ITEM || ltype == Type.ATOMIC) {
			if (Type.subTypeOf(rtype, Type.NUMBER)) {
				lv = lv.convertTo(Type.DOUBLE);
			} else if (rtype == Type.ITEM || rtype == Type.ATOMIC) {
				lv = lv.convertTo(Type.STRING);
				rv = rv.convertTo(Type.STRING);
			} else
				lv = lv.convertTo(rv.getType());
		} else if (rtype == Type.ITEM || rtype == Type.ATOMIC) {
			if (Type.subTypeOf(ltype, Type.NUMBER)) {
				rv = rv.convertTo(Type.DOUBLE);
			} else if (rtype == Type.ITEM || rtype == Type.ATOMIC) {
				lv = lv.convertTo(Type.STRING);
				rv = rv.convertTo(Type.STRING);
			} else
				rv = rv.convertTo(lv.getType());
		}
		if (context.isBackwardsCompatible()) {
			// in XPath 1.0 compatible mode, if one of the operands is a number, cast
			// both operands to xs:double
			if (Type.subTypeOf(ltype, Type.NUMBER)
				|| Type.subTypeOf(rtype, Type.NUMBER)) {
				lv = lv.convertTo(Type.DOUBLE);
				rv = rv.convertTo(Type.DOUBLE);
			}
		}
//				System.out.println(
//					lv.getStringValue() + Constants.OPS[relation] + rv.getStringValue());
		return lv.compareTo(relation, rv);
	}

	private boolean checkArgumentTypes(StaticContext context, DocumentSet docs)
		throws XPathException {
		Configuration config = context.getBroker().getConfiguration();
		Map idxPathMap = (Map) config.getProperty("indexer.map");
		DocumentImpl doc;
		IndexPaths idx;
		for (Iterator i = docs.iterator(); i.hasNext();) {
			doc = (DocumentImpl) i.next();
			idx = (IndexPaths) idxPathMap.get(doc.getDoctype().getName());
			if (idx != null && idx.isSelective())
				return true;
			if (idx != null && (!idx.getIncludeAlphaNum()))
				return true;
		}
		return false;
	}

	private String maskWildcards(String expr) {
		StringBuffer buf = new StringBuffer();
		char ch;
		for (int i = 0; i < expr.length(); i++) {
			ch = expr.charAt(i);
			switch (ch) {
				case '*' :
					buf.append("\\*");
					break;
				case '%' :
					buf.append('*');
					break;
				default :
					buf.append(ch);
			}
		}
		return buf.toString();
	}

	/* (non-Javadoc)
	 * @see org.exist.xpath.Expression#pprint()
	 */
	public String pprint() {
		StringBuffer buf = new StringBuffer();
		buf.append(getLeft().pprint());
		buf.append(' ');
		buf.append(Constants.OPS[relation]);
		buf.append(' ');
		buf.append(getRight().pprint());
		return buf.toString();
	}

	protected void switchOperands() {
		switch (relation) {
			case Constants.GT :
				relation = Constants.LT;
				break;
			case Constants.LT :
				relation = Constants.GT;
				break;
			case Constants.LTEQ :
				relation = Constants.GTEQ;
				break;
			case Constants.GTEQ :
				relation = Constants.LTEQ;
				break;
		}
		Expression right = getRight();
		setRight(getLeft());
		setLeft(right);
	}

	protected void simplify() {
		// switch operands to simplify execution
		if ((!Type.subTypeOf(getLeft().returnsType(), Type.NODE))
			&& Type.subTypeOf(getRight().returnsType(), Type.NODE))
			switchOperands();
		else if (
			(getLeft().getCardinality() & Cardinality.MANY) != 0
				&& (getRight().getCardinality() & Cardinality.MANY) == 0)
			switchOperands();
	}
}
