package no.skaperiet.xmldb.query;

public interface Parameter {
    public String getName();
    public Object getValue();
    public boolean compare(Parameter otherParam);
}
