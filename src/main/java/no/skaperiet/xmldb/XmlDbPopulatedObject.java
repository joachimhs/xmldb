package no.skaperiet.xmldb;

import java.sql.ResultSet;
import java.sql.SQLException;

public interface XmlDbPopulatedObject {
	public void populate(ResultSet resultset) throws SQLException;
}
