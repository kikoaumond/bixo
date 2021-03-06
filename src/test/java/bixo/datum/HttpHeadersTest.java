package bixo.datum;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import cascading.tuple.Tuple;


public class HttpHeadersTest {

    @Test
    public void testMultiValues() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("key", "value1");
        headers.add("key", "value2");
        
        assertEquals("value1", headers.getFirst("key"));
        List<String> values = headers.getAll("key");
        assertEquals(2, values.size());
        
        Collections.sort(values);
        assertEquals("value1", values.get(0));
        assertEquals("value2", values.get(1));
    }
    
    @Test
    public void testEncodeDecode() {
        HttpHeaders headers = new HttpHeaders();
        String key1 = "key\twith\ttabs";
        String value1 = "value1";
        headers.add(key1, value1);
        
        Tuple t = headers.toTuple();
        HttpHeaders newHeaders = new HttpHeaders(t);
        assertEquals(1, newHeaders.getNames().size());
        assertEquals(value1, newHeaders.getFirst(key1));
        
        String key2 = "key\n\r\fwith lots of funky chars";
        String value2 = "value2";
        headers.add(key2, value2);
        
        t = headers.toTuple();
        newHeaders = new HttpHeaders(t);
        assertEquals(2, newHeaders.getNames().size());
        assertEquals(value1, newHeaders.getFirst(key1));
        assertEquals(value2, newHeaders.getFirst(key2));
    }
    
    @Test
    public void testSerialization() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("key1", "value1");
        headers.add("key1", "value2");
        headers.add("key2", "value3");

        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutput out = new DataOutputStream(byteStream);
        headers.write(out);
        
        HttpHeaders newHeaders = new HttpHeaders();
        DataInput in = new DataInputStream(new ByteArrayInputStream(byteStream.toByteArray()));
        newHeaders.readFields(in);
        
        assertEquals("value1", newHeaders.getFirst("key1"));
        List<String> values = newHeaders.getAll("key1");
        assertEquals(2, values.size());
        
        Collections.sort(values);
        assertEquals("value1", values.get(0));
        assertEquals("value2", values.get(1));

        values = newHeaders.getAll("key2");
        assertEquals(1, values.size());
        
        assertEquals(2, newHeaders.getNames().size());
    }
}
