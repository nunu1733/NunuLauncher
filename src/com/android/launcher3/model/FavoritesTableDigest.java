package com.android.launcher3.model;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.provider.LauncherDbUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class FavoritesTableDigest {
    static final int VERSION = 1;

    private static final byte[] DOMAIN_PREFIX =
            "nunu/grid-migration/favorites-backup".getBytes(StandardCharsets.UTF_8);

    private FavoritesTableDigest() { }

    static String digest(SQLiteDatabase database, String tableName) {
        if (!LauncherDbUtils.tableExists(database, tableName)) {
            throw new IllegalStateException("Favorites digest table is missing: " + tableName);
        }

        List<Column> columns = readColumns(database, tableName);
        if (columns.isEmpty()
                || columns.stream().noneMatch(column -> Favorites._ID.equals(column.name()))) {
            throw new IllegalStateException("Favorites digest schema is missing required columns");
        }

        MessageDigest digest = sha256();
        digest.update(DOMAIN_PREFIX);
        digest.update((byte) 0);
        writeInt(digest, VERSION);
        writeInt(digest, columns.size());
        for (Column column : columns) {
            writeBytes(digest, column.name().getBytes(StandardCharsets.UTF_8));
        }

        String projection = String.join(",", columns.stream()
                .map(column -> quoteIdentifier(column.name()))
                .toList());
        String query = "SELECT " + projection + " FROM " + quoteIdentifier(tableName)
                + " ORDER BY " + quoteIdentifier(Favorites._ID);
        try (Cursor cursor = database.rawQuery(query, null)) {
            writeInt(digest, cursor.getCount());
            while (cursor.moveToNext()) {
                for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                    writeValue(digest, cursor, columnIndex);
                }
            }
        }
        return lowerHex(digest.digest());
    }

    private static List<Column> readColumns(SQLiteDatabase database, String tableName) {
        List<Column> columns = new ArrayList<>();
        try (Cursor cursor = database.rawQuery(
                "PRAGMA table_info(" + quoteIdentifier(tableName) + ")", null)) {
            int cidIndex = cursor.getColumnIndexOrThrow("cid");
            int nameIndex = cursor.getColumnIndexOrThrow("name");
            while (cursor.moveToNext()) {
                columns.add(new Column(cursor.getInt(cidIndex), cursor.getString(nameIndex)));
            }
        }
        columns.sort(Comparator.comparingInt(Column::cid));
        for (int index = 0; index < columns.size(); index++) {
            if (columns.get(index).cid() != index) {
                throw new IllegalStateException("Favorites digest schema has invalid column ids");
            }
        }
        return columns;
    }

    private static void writeValue(MessageDigest digest, Cursor cursor, int columnIndex) {
        int type = cursor.getType(columnIndex);
        digest.update((byte) type);
        switch (type) {
            case Cursor.FIELD_TYPE_NULL:
                return;
            case Cursor.FIELD_TYPE_INTEGER:
                writeLong(digest, cursor.getLong(columnIndex));
                return;
            case Cursor.FIELD_TYPE_FLOAT:
                writeLong(digest, Double.doubleToLongBits(cursor.getDouble(columnIndex)));
                return;
            case Cursor.FIELD_TYPE_STRING:
                writeBytes(digest,
                        cursor.getString(columnIndex).getBytes(StandardCharsets.UTF_8));
                return;
            case Cursor.FIELD_TYPE_BLOB:
                writeBytes(digest, cursor.getBlob(columnIndex));
                return;
            default:
                throw new IllegalStateException("Unsupported SQLite type: " + type);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void writeBytes(MessageDigest digest, byte[] value) {
        writeInt(digest, value.length);
        digest.update(value);
    }

    private static void writeInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void writeLong(MessageDigest digest, long value) {
        digest.update((byte) (value >>> 56));
        digest.update((byte) (value >>> 48));
        digest.update((byte) (value >>> 40));
        digest.update((byte) (value >>> 32));
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String lowerHex(byte[] value) {
        char[] hex = new char[value.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int index = 0; index < value.length; index++) {
            int unsigned = value[index] & 0xff;
            hex[index * 2] = alphabet[unsigned >>> 4];
            hex[index * 2 + 1] = alphabet[unsigned & 0x0f];
        }
        return new String(hex);
    }

    private record Column(int cid, String name) { }
}
