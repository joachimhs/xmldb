package no.skaperiet.xmldb.query;

/**
 * Created by IntelliJ IDEA.
 * User: jhb
 * Date: 14/05/2005
 * Time: 15:59:56
 * To change this template use File | Settings | File Templates.
 */
public class NamedParameter
{
	private Object value;
	private String name;

	private String type = "string";

	public NamedParameter(String name, int index)
	{
		this.name = name;
	}

	public NamedParameter(String name, Object value)
	{
		this.name = name;
		this.value = value;
	}

	public NamedParameter(int index, String name, Object value, String type)
	{
		this.name = name;
		this.value = value;
		this.type = type;
	}

	public NamedParameter(int index, String name)
	{
		this(name, index);
	}

	public String getName()
	{
		return name;
	}

	public Object getValue()
	{
		return value;
	}

	public String getType()
	{
		return type;
	}

	public void setType(String type)
	{
		this.type = type;
	}

	public boolean compare(NamedParameter param)
	{
		return this.name.equals(param.getName());
	}

}