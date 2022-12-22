package no.skaperiet.xmldb.query;

/**
 * Created by IntelliJ IDEA.
 * User: jhb
 * Date: 14/05/2005
 * Time: 15:59:56
 * To change this template use File | Settings | File Templates.
 */
public class Parameter
{
	private Object value;
	private String name;
	private int index;

	private String type = "string";

	public Parameter(String name, int index)
	{
		this.name = name;
		this.index = index;
	}

	public Parameter(int index, String name, Object value)
	{
		this.index = index;
		this.name = name;
		this.value = value;
	}

	public Parameter(int index, String name, Object value, String type)
	{
		this.index = index;
		this.name = name;
		this.value = value;
		this.type = type;
	}

	public Parameter(int index, String name)
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

	public int getIndex()
	{
		return index;
	}

	public String getType()
	{
		return type;
	}

	public void setType(String type)
	{
		this.type = type;
	}

	public String toString()
	{
		return name + " - " + value + "(" + index + ", " + type + ")";
	}

	public boolean compare(Parameter param)
	{
		return this.index == param.getIndex() && this.name.equals(param.getName());
	}

}