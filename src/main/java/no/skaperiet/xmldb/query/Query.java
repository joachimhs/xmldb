package no.skaperiet.xmldb.query;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.Vector;
import java.util.NoSuchElementException;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: jhb
 * Date: 14/05/2005
 * Time: 15:58:49
 * To change this template use File | Settings | File Templates.
 */
public class Query
{
	private String queryName;
	private String query;
	private Vector parameters;

	public Query(Element element)
	{
		queryName = element.getAttribute("name");
		parameters = readParameters(element.getElementsByTagName("param"));
		query = readQuery(element.getElementsByTagName("query"));
	}

	public Vector readParameters(NodeList elements)
	{
		Element element;
		Parameter parameter;

		Vector params = new Vector();

		for (int i = 0; i < elements.getLength(); i++)
		{
			element = (Element)elements.item(i);

			parameter = new Parameter(element.getAttribute("name"),
									 Integer.parseInt(element.getAttribute("index")));

			String type = element.getAttribute("type");
			if (type != null && type != "")
				parameter.setType(type);

			params.add(parameter);
		}

		return params;
	}

	public String readQuery(NodeList elements)
	{
		if (elements.getLength() > 0)
		{
			Element element = (Element)elements.item(0);

			return element.getFirstChild().getNodeValue();
		}
		else
			throw new NoSuchElementException( "No query elements in the node" );

	}

	public String getName()
	{
		return queryName;
	}

	public String getQuery()
	{
		return query;
	}

	public String getParametersAsString()
	{
		return parameters.toString();
	}

	public boolean compare(List params)
	{
		//Return false unless match is found
		boolean match = false;

		//If parameters does not match, return false
		if (params.size() != parameters.size())
			return false;

		//If no parameters are given and noone are needed, return true
		if (parameters.size() == 0 && params.size() == 0)
			return true;

		//Iterate parameters
		for (int i = 0; i < params.size(); i++)
		{
			Parameter otherParam = (Parameter)params.get(i);

			//Assume a match is not found
			match = false;
			//Iterate parameters for this query to see if a match is found
			for (int j = 0; j < parameters.size(); j++)
			{
				Parameter thisParam = (Parameter)parameters.elementAt(j);
				//if otherParam is part of this query, flag match = true
				if (otherParam.compare(thisParam))
					match = true;
			}

			//If the parameter was not part of the parameter list,
			//break and return false straight away
			if (!match)
				break;

		}

		//Returns true only if all parameters matched
		return match;
	}

}