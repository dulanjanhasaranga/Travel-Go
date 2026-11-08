import java.nio.file.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/** Read-only inspection of public catalogue tables; never connects to a remote database. */
public class InspectLocalCatalogue {
    public static void main(String[] args) throws Exception {
        Path config = Path.of("local.properties");
        Properties settings = new Properties();
        for (String line : Files.readAllLines(config)) {
            if (line.matches("\\s*(spring\\.datasource\\.(url|username|password)|TRAVELGO_DB_(URL|USERNAME|PASSWORD))\\s*[:=].*"))
                settings.load(new StringReader(line));
        }
        String url = settings.getProperty("spring.datasource.url", settings.getProperty("TRAVELGO_DB_URL", "jdbc:mysql://localhost:3306/travelgo"));
        String username = settings.getProperty("spring.datasource.username", settings.getProperty("TRAVELGO_DB_USERNAME", "root"));
        String password = settings.getProperty("spring.datasource.password", settings.getProperty("TRAVELGO_DB_PASSWORD", ""));
        if (!url.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1)(:3306)?/[a-zA-Z0-9_]+(\\?.*)?"))
            throw new IllegalStateException("Refused: inspection requires the existing local MySQL on port3306.");
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            connection.setReadOnly(true);
            System.out.println("Read-only local catalogue inspection; database=" + connection.getCatalog());
            for (String table : List.of("destinations", "package_categories", "tour_packages", "hotels", "transports", "departures", "package_images", "visa_checklists", "visa_checklist_items")) {
                try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    result.next(); System.out.println(table + ": " + result.getLong(1));
                } catch (SQLException missing) { System.out.println(table + ": SQLState=" + missing.getSQLState()); }
            }
        } catch (SQLException failure) {
            System.out.println("Local inspection failed: SQLState=" + failure.getSQLState() + ", code=" + failure.getErrorCode());
            System.exit(1);
        }
    }
}
