package org.example.db.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.db.config.DbExplorerProperties;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryResultReaderTest {
    private ResultSet textRows(String... rows) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("text");
        when(meta.getColumnType(1)).thenReturn(Types.CLOB);
        when(meta.getColumnTypeName(1)).thenReturn("CLOB");
        var index = new java.util.concurrent.atomic.AtomicInteger(-1);
        when(rs.next()).thenAnswer(i -> index.incrementAndGet() < rows.length);
        when(rs.getCharacterStream(1)).thenAnswer(i -> new StringReader(rows[index.get()]));
        return rs;
    }

    @Test void boundsClobBeforeSerializationAndIndicatesTruncation() throws Exception {
        var props = new DbExplorerProperties();
        props.setFieldMaxLength(64);
        var result = new QueryResultReader(props, new ObjectMapper()).read(textRows("x".repeat(10000)), 10);
        assertThat(result.rows().getFirst().get("text")).isEqualTo("x".repeat(64));
        assertThat(result.limited()).isTrue();
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test void budgetsJsonEscapingBytesAndStopsAtRowBoundary() throws Exception {
        var props = new DbExplorerProperties();
        props.setResultMaxBytes(1024);
        var result = new QueryResultReader(props, new ObjectMapper()).read(textRows("\"".repeat(300), "\"".repeat(300)), 10);
        assertThat(result.rows()).hasSize(1);
        assertThat(result.limited()).isTrue();
        assertThat(new ObjectMapper().writeValueAsBytes(result).length).isLessThan(1024);
    }

    @Test void binaryValuesAreDetachedBase64AndStreamIsClosed() throws Exception {
        ResultSet rs = textRows("unused");
        ResultSetMetaData meta = rs.getMetaData();
        when(meta.getColumnType(1)).thenReturn(Types.BLOB);
        InputStream stream = spy(new ByteArrayInputStream(new byte[]{1,2,3}));
        when(rs.getBinaryStream(1)).thenReturn(stream);
        var result = new QueryResultReader(new DbExplorerProperties(), new ObjectMapper()).read(rs, 10);
        assertThat(result.rows().getFirst().get("text")).isEqualTo("AQID");
        verify(stream).close();
    }
}
