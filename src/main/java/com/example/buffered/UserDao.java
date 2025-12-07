package com.example.buffered;

import java.sql.*;

public class UserDao {
    private final String url, user, pass;

    public UserDao(String url, String user, String pass) {
        this.url = url; this.user = user; this.pass = pass;
    }

    public UserRecord findByUsername(String username) {
        String sql = "SELECT USER_ID, USERNAME, PASSWORD, LOCK_STATUS, FAILED_COUNT " +
                     "FROM USERS WHERE USERNAME = ?";
        try (Connection c = DriverManager.getConnection(url, user, pass);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            return new UserRecord(
                    rs.getLong(1), rs.getString(2),
                    rs.getString(3), rs.getString(4),
                    rs.getInt(5));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}

