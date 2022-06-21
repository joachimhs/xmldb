package no.skaperiet.xmldb.query;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class QueryTree
{
	private HashSet queries;
	private static Logger log = Logger.getLogger(QueryTree.class);

	public QueryTree() {
		queries = new HashSet();
	}

	public QueryTree (String elemName, Element root)
	{
		queries = new HashSet();

		NodeList elements = root.getElementsByTagName(elemName);

		for (int i = 0; i < elements.getLength(); i++)
		{
			Query query = new Query( (Element)elements.item(i));

			if(!queries.add(query)) {
				System.err.println("unable to add query");
			} else {
				log.debug("Added Query: " + query.getName() + " ::: " + query.getParametersAsString());
			}
		}
	}

	public QueryTree (String elemName, String uri)
	{
		this( elemName, openDocument(uri).getDocumentElement());
	}

	public void appendQueriesFromFile(String elemName, String uri) {
		NodeList elements = openDocument(uri).getDocumentElement().getElementsByTagName(elemName);
		for (int i = 0; i < elements.getLength(); i++) {
			Query query = new Query( (Element)elements.item(i));
			if (!queries.add(query)) {
				System.err.println("Unable to Append Query: " + query.getName() + ":::" + query.getParametersAsString());
			} else {
				log.debug("Appended Query: " + query.getName() + " ::: " + query.getParametersAsString());
			}
		}
	}

	public static Document openDocument(String uri)
	{
		try
		{
			DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();

			return builder.parse(uri);
		}
		catch (Exception e)
		{
			System.err.println("QueryTree:openDocument(): " + e);
			return null;
		}
	}

	public void appendQueriesFromInputStream(String elemName, InputStream inputStream) {
		NodeList elements = openDocument(inputStream).getDocumentElement().getElementsByTagName(elemName);
		for (int i = 0; i < elements.getLength(); i++) {
			Query query = new Query( (Element)elements.item(i));
			if (!queries.add(query)) {
				System.err.println("Unable to Append Query: " + query.getName() + ":::" + query.getParametersAsString());
			} else {
				log.debug("Appended Query: " + query.getName() + " ::: " + query.getParametersAsString());
			}
		}
	}

	public static Document openDocument(InputStream inputStream) {
		try
		{
			DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();

			return builder.parse(inputStream);
		}
		catch (ParserConfigurationException e)
		{
			System.err.println("Unable to configure Parser: " + e);
			e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
		}
		catch (SAXException e)
		{
			System.err.println("Unable to read XML: " + e);
			e.printStackTrace();  //To change bodsy of catch statement use File | Settings | File Templates.
		}
		catch (IOException e)
		{
			System.err.println("Unable to read File: " + e);
			e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
		}

		return null;
	}

	/*public Query getQuery(String name)
	{
		return (Query)queries.get(name);
	}*/

	public Query getQuery(String name, List params)
	{
		boolean match = true;
		Query query = null;
		Iterator iter = queries.iterator();

		while (iter.hasNext())
		{
			query = (Query)iter.next();

			//log.debug("Iterating..." + query.getName());
			//iter.next();
			if ((query.getName()).equals(name))
			{
				//System.err.println("Query matches: " + name);
				if (query.compare(params))
				{
					//log.error("Params matches: " + params.toString());
					return query;
				}
			}
		}

		return null;
		/*
		Query query = new getQuery(name);

		match = query.compare(params);

		if (match) return query;
		else return null;
		*/
	}
}