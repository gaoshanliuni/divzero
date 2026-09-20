package dev.mineagent.runtime.worker.web;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import static org.junit.jupiter.api.Assertions.*;
class PublicHttpsReaderTest {
    @Test void honorsExplicitNoProxyWithoutSuffixConfusion(){assertTrue(PublicHttpsReader.noProxy("www.example.com",".example.com"));assertFalse(PublicHttpsReader.noProxy("notexample.com","example.com"));assertTrue(PublicHttpsReader.noProxy("example.com","*"));}
    @Test void rejectsCredentialsLocalSchemesPortsAndIpLiterals(){
        for(String value:new String[]{"file:///secret","http://example.com","https://u:p@example.com/","https://example.com:8443/","https://127.0.0.1/","https://[::1]/","https://example.com/\r\nX: y"})assertThrows(IllegalArgumentException.class,()->PublicHttpsReader.address(value));
        assertEquals("https://example.com/guide",PublicHttpsReader.address("https://example.com/guide#section").toString());
    }
    @Test void checksEverySpecialAddressFamilyBeforeConnecting()throws Exception{
        for(String value:new String[]{"127.0.0.1","10.1.2.3","172.16.0.2","192.168.0.3","169.254.169.254","100.64.0.1","198.18.0.1","192.0.2.1","198.51.100.1","203.0.113.1","224.0.0.1","::1","fc00::1","fe80::1","2001:db8::1","2002:c0a8:1::1","::ffff:127.0.0.1"})assertFalse(PublicHttpsReader.publicAddress(InetAddress.getByName(value)),value);
        assertTrue(PublicHttpsReader.publicAddress(InetAddress.getByName("8.8.8.8")));assertTrue(PublicHttpsReader.publicAddress(InetAddress.getByName("2001:4860:4860::8888")));
    }
}
