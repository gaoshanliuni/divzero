package dev.mineagent.runtime.core.host;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HostCommandRequestTest {
    @Test void exactApprovalHashChangesWithCodePurposeOrTimeout(){var id=UUID.randomUUID();var base=new HostCommandRequest(id,"read app","Get-Process",5);assertNotEquals(base.sha256(),new HostCommandRequest(id,"read app","Get-Service",5).sha256());assertNotEquals(base.sha256(),new HostCommandRequest(id,"different task","Get-Process",5).sha256());assertNotEquals(base.sha256(),new HostCommandRequest(id,"read app","Get-Process",6).sha256());}
    @Test void misleadingControlsExcessiveCodeAndTimeoutAreRejected(){var id=UUID.randomUUID();assertThrows(IllegalArgumentException.class,()->new HostCommandRequest(id,"task","abc\u202e",5));assertThrows(IllegalArgumentException.class,()->new HostCommandRequest(id,"task\nnext","Get-Process",5));assertThrows(IllegalArgumentException.class,()->new HostCommandRequest(id,"task","x".repeat(8193),5));assertThrows(IllegalArgumentException.class,()->new HostCommandRequest(id,"task","Get-Process",61));}
}
