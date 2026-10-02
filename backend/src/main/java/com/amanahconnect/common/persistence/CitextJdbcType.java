package com.amanahconnect.common.persistence;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.BasicBinder;
import org.hibernate.type.descriptor.jdbc.VarcharJdbcType;
import org.postgresql.util.PGobject;

/**
 * Binds String parameters as a real {@code citext}.
 *
 * <p>With a plain varchar parameter PostgreSQL compares {@code citext_column = $1} as {@code text},
 * which is case-sensitive and cannot use the citext index, so {@code findByEmail("bob@x.com")} would
 * silently miss {@code Bob@X.com}. Typing the parameter as citext keeps the comparison
 * case-insensitive and indexed. Use together with {@code @Column(columnDefinition = "citext")}.
 */
public class CitextJdbcType extends VarcharJdbcType {

    public static final CitextJdbcType INSTANCE = new CitextJdbcType();

    @Override
    public <X> ValueBinder<X> getBinder(JavaType<X> javaType) {
        return new BasicBinder<>(javaType, this) {
            @Override
            protected void doBind(PreparedStatement st, X value, int index, WrapperOptions options)
                    throws SQLException {
                st.setObject(index, citext(javaType.unwrap(value, String.class, options)));
            }

            @Override
            protected void doBind(CallableStatement st, X value, String name, WrapperOptions options)
                    throws SQLException {
                st.setObject(name, citext(javaType.unwrap(value, String.class, options)));
            }
        };
    }

    private static PGobject citext(String value) throws SQLException {
        PGobject object = new PGobject();
        object.setType("citext");
        object.setValue(value);
        return object;
    }
}
