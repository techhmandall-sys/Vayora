package com.dypcet.vayora.identity;

import java.util.List;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IdentityService {

    public record User(long id, String email, String fullName, UserRole role) {
    }

    private record Credentials(User user, String passwordHash, String status) {
    }

    static final String SUSPENDED = "This account is suspended";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final SignInThrottle throttle;

    public IdentityService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, SignInThrottle throttle) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.throttle = throttle;
    }

    public User register(String email, String password, String fullName, UserRole role) {
        return insert(normalise(email), null, password, fullName, role);
    }

    /** An account known by its phone number, which is unconfirmed until a code proves it. */
    public User registerByPhone(String phone, String password, String fullName, UserRole role) {
        String normalised = Phones.normalise(phone);
        if (normalised == null || !normalised.matches("\\+?[0-9]{10,15}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a phone number of 10 to 15 digits");
        }
        return insert(null, normalised, password, fullName, role);
    }

    private User insert(String email, String phone, String password, String fullName, UserRole role) {
        if (role == UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Administrators cannot self-register");
        }
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO users (email, phone, password_hash, full_name, role)
                    VALUES (?, ?, ?, ?, ?) RETURNING id""",
                    Long.class, email, phone, passwordEncoder.encode(password), fullName.strip(),
                    role.name());
            return new User(id, email, fullName.strip(), role);
        } catch (DuplicateKeyException e) {
            if (email != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone number is already registered");
        }
    }

    /** Creates an administrator unless the email is already taken. Returns true if created. */
    public boolean ensureAdmin(String email, String password) {
        return jdbc.update("""
                INSERT INTO users (email, password_hash, full_name, role)
                VALUES (?, ?, 'Administrator', 'ADMIN')
                ON CONFLICT (email) DO NOTHING""",
                normalise(email), passwordEncoder.encode(password)) == 1;
    }

    /** Fails with the same 401 whether the email is unknown or the password is wrong. */
    public User authenticate(String email, String password) {
        String normalised = normalise(email);
        return check("email:" + normalised, credentials("email", normalised), password);
    }

    /** As {@link #authenticate}, for a member who signs in with their phone number. */
    public User authenticateByPhone(String phone, String password) {
        String normalised = Phones.normalise(phone);
        return check("phone:" + normalised, credentials("phone", normalised), password);
    }

    /** @param column a fixed column name, never caller input */
    private List<Credentials> credentials(String column, String value) {
        return jdbc.query("SELECT id, email, full_name, role, password_hash, status FROM users WHERE "
                        + column + " = ?",
                (rs, n) -> new Credentials(
                        new User(rs.getLong("id"), rs.getString("email"),
                                rs.getString("full_name"), UserRole.valueOf(rs.getString("role"))),
                        rs.getString("password_hash"), rs.getString("status")),
                value);
    }

    /** @param who what the throttle counts wrong passwords against */
    private User check(String who, List<Credentials> found, String password) {
        throttle.refuseIfLocked(who);
        if (found.isEmpty() || !passwordEncoder.matches(password, found.get(0).passwordHash())) {
            throttle.failed(who);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email, phone number or password");
        }
        throttle.succeeded(who);
        // Only said to someone who has proved they own the account.
        if ("SUSPENDED".equals(found.get(0).status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, SUSPENDED);
        }
        return found.get(0).user();
    }

    public User get(long id) {
        List<User> found = jdbc.query(
                "SELECT id, email, full_name, role FROM users WHERE id = ?",
                (rs, n) -> new User(rs.getLong("id"), rs.getString("email"),
                        rs.getString("full_name"), UserRole.valueOf(rs.getString("role"))),
                id);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        return found.get(0);
    }

    private static String normalise(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
