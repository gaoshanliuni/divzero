package dev.mineagent.runtime.worker.web;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Public HTTPS GET only. DNS is checked once and the checked address is pinned to the TLS socket. */
public final class PublicHttpsReader {
    public static final int MAX_BYTES=768*1024;
    private final int maxBytes;
    public PublicHttpsReader(){this(MAX_BYTES);}
    public PublicHttpsReader(int maxBytes){if(maxBytes<1||maxBytes>64*1024*1024)throw new IllegalArgumentException("WEB_RESPONSE_BUDGET");this.maxBytes=maxBytes;}
    private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES=new java.util.concurrent.ScheduledThreadPoolExecutor(1,r->{var t=new Thread(r,"public-web-deadline");t.setDaemon(true);return t;});
    static {DEADLINES.setRemoveOnCancelPolicy(true);}
    public record Page(URI url,int status,String contentType,byte[] body,String fetchedAt){}
    private record Response(int status,Map<String,String> headers,byte[] body){}
    public static URI address(String value){
        try{
            URI u=URI.create(value.split("#",2)[0]);String host=u.getHost();
            if(value.length()>2048||!"https".equalsIgnoreCase(u.getScheme())||host==null||u.getRawUserInfo()!=null||(u.getPort()!=-1&&u.getPort()!=443)||!host.matches("(?i)[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")||!host.contains(".")||host.endsWith(".")||host.matches("[0-9.]+")||value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();
            return URI.create(u.toASCIIString());
        }catch(RuntimeException invalid){throw new IllegalArgumentException("WEB_PUBLIC_HTTPS_REQUIRED");}
    }
    public static boolean publicAddress(InetAddress ip){
        if(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress())return false;
        byte[] b=ip.getAddress();if(b.length==16)return (b[0]&0xe0)==0x20&&!((b[0]&255)==0x20&&(b[1]&255)==1&&((b[2]&255)==0&&(b[3]&255)==0||(b[2]&255)==0x0d&&(b[3]&255)==0xb8))&&!((b[0]&255)==0x20&&(b[1]&255)==2);
        if(b.length!=4)return false;int a=b[0]&255,c=b[1]&255,d=b[2]&255;
        return a!=0&&a<224&&!(a==100&&c>=64&&c<=127)&&!(a==198&&(c==18||c==19||c==51&&d==100))&&!(a==192&&c==0&&(d==0||d==2))&&!(a==203&&c==0&&d==113);
    }
    public Page get(String url)throws Exception{
        URI uri=address(url);long deadline=System.nanoTime()+java.time.Duration.ofSeconds(30).toNanos();
        for(int hop=0;hop<4;hop++){
            Proxy proxy=selectProxy(uri);InetAddress[] addresses=resolve(uri,proxy,deadline);if(addresses.length==0||Arrays.stream(addresses).anyMatch(ip->!publicAddress(ip)))throw new IOException("WEB_PRIVATE_ADDRESS_REJECTED");
            Response response=request(uri,addresses[0],deadline,proxy);
            if(Set.of(301,302,303,307,308).contains(response.status())){String location=response.headers().get("location");if(location==null)throw new IOException("WEB_REDIRECT_WITHOUT_LOCATION");uri=address(uri.resolve(location).toString());continue;}
            String type=response.headers().getOrDefault("content-type","");byte[] body=response.body();String encoding=response.headers().getOrDefault("content-encoding","identity").toLowerCase(Locale.ROOT);
            if(encoding.equals("gzip")){try(var in=new GZIPInputStream(new ByteArrayInputStream(body))){body=readBounded(in,maxBytes);}}else if(!encoding.equals("identity"))throw new IOException("WEB_CONTENT_ENCODING_UNSUPPORTED");
            return new Page(uri,response.status(),type,body,Instant.now().toString());
        }
        throw new IOException("WEB_REDIRECT_LIMIT");
    }
    static boolean noProxy(String host,String value){
        if(value==null)return false;for(String part:value.split(",")){String rule=part.strip().toLowerCase(Locale.ROOT);if(rule.equals("*"))return true;if(rule.startsWith("*."))rule=rule.substring(1);if(rule.startsWith("."))rule=rule.substring(1);if(rule.contains(":"))rule=rule.substring(0,rule.indexOf(':'));if(!rule.isBlank()&&(host.equalsIgnoreCase(rule)||host.toLowerCase(Locale.ROOT).endsWith("."+rule)))return true;}return false;
    }
    private static Proxy selectProxy(URI uri){
        if(noProxy(uri.getHost(),System.getenv().getOrDefault("NO_PROXY",System.getenv("no_proxy"))))return Proxy.NO_PROXY;
        String configured=System.getenv("HTTPS_PROXY");if(configured==null||configured.isBlank())configured=System.getenv("https_proxy");
        if(configured!=null&&!configured.isBlank()){
            URI endpoint=URI.create(configured);if(!"http".equalsIgnoreCase(endpoint.getScheme())||endpoint.getHost()==null||endpoint.getUserInfo()!=null)throw new IllegalArgumentException("WEB_PROXY_CONFIG_UNSUPPORTED");
            return new Proxy(Proxy.Type.HTTP,new InetSocketAddress(endpoint.getHost(),endpoint.getPort()<0?80:endpoint.getPort()));
        }
        var selector=ProxySelector.getDefault();if(selector==null)return Proxy.NO_PROXY;
        for(var proxy:selector.select(uri)){if(proxy.type()==Proxy.Type.HTTP)return proxy;if(proxy.type()==Proxy.Type.DIRECT)return Proxy.NO_PROXY;}
        return Proxy.NO_PROXY;
    }
    private static InetAddress[] resolve(URI uri,Proxy proxy,long deadline)throws Exception{
        if(proxy.type()!=Proxy.Type.HTTP)return InetAddress.getAllByName(uri.getHost());
        // With an explicitly OS-configured proxy, avoid poisoned/local DNS. Resolve over a pinned
        // public DoH endpoint, then CONNECT to the checked IP, never an unchecked proxy-resolved host.
        URI dns=URI.create("https://cloudflare-dns.com/dns-query?name="+URLEncoder.encode(uri.getHost(),StandardCharsets.UTF_8)+"&type=A");
        var answer=new PublicHttpsReader().request(dns,InetAddress.getByAddress(new byte[]{1,1,1,1}),deadline,selectProxy(dns));
        if(answer.status()!=200)throw new IOException("WEB_DNS_FAILED");var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(answer.body());
        if(json.path("Status").asInt(-1)!=0)throw new IOException("WEB_DNS_FAILED");var ips=new ArrayList<InetAddress>();
        for(var row:json.path("Answer"))if(row.path("type").asInt()==1){String ip=row.path("data").asText();if(!ip.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}"))throw new IOException("WEB_DNS_RESPONSE_INVALID");ips.add(InetAddress.getByName(ip));}
        if(ips.isEmpty())throw new IOException("WEB_DNS_NO_PUBLIC_IPV4");return ips.toArray(InetAddress[]::new);
    }
    private Response request(URI uri,InetAddress ip,long deadline,Proxy proxy)throws Exception{
        int remaining=remaining(deadline);try(var raw=new Socket(Proxy.NO_PROXY)){
            var expiry=DEADLINES.schedule(()->{try{raw.close();}catch(IOException ignored){}},remaining,java.util.concurrent.TimeUnit.MILLISECONDS);
            try{
            raw.connect(proxy.type()==Proxy.Type.HTTP?proxy.address():new InetSocketAddress(ip,443),Math.min(10000,remaining));raw.setSoTimeout(Math.min(15000,remaining(deadline)));
            if(proxy.type()==Proxy.Type.HTTP){
                String authority=(ip.getAddress().length==16?"["+ip.getHostAddress()+"]":ip.getHostAddress())+":443";
                raw.getOutputStream().write(("CONNECT "+authority+" HTTP/1.1\r\nHost: "+authority+"\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));raw.getOutputStream().flush();
                var tunnel=raw.getInputStream();String status=line(tunnel,4096,deadline);if(!status.matches("HTTP/1\\.[01] 200(?: .*)?"))throw new IOException("WEB_PROXY_CONNECT_FAILED");
                int headers=0,total=0;for(String row;!(row=line(tunnel,8192,deadline)).isEmpty();)if(++headers>100||(total+=row.length())>32768)throw new IOException("WEB_PROXY_HEADERS_LIMIT");
            }
            try(var tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(raw,uri.getHost(),443,true)){
                var params=tls.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");params.setServerNames(List.of(new SNIHostName(uri.getHost())));tls.setSSLParameters(params);tls.startHandshake();
                String target=uri.getRawPath();if(target==null||target.isEmpty())target="/";if(uri.getRawQuery()!=null)target+="?"+uri.getRawQuery();
                String headers="GET "+target+" HTTP/1.1\r\nHost: "+uri.getHost()+"\r\nUser-Agent: Mozilla/5.0 (compatible; DivZero/0.1; +https://github.com/gaoshanliuni/divzero)\r\nAccept: text/html,application/xhtml+xml,text/plain,application/json,application/dns-json,application/xml\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
                if(uri.getHost().equals("cloudflare-dns.com"))headers=headers.replace("Accept: text/html,application/xhtml+xml,text/plain,application/json,application/dns-json,application/xml","Accept: application/dns-json");
                tls.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));tls.getOutputStream().flush();var in=new BufferedInputStream(tls.getInputStream());
                String status=line(in,4096,deadline);if(!status.matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?"))throw new IOException("WEB_HTTP_STATUS_INVALID");int code=Integer.parseInt(status.substring(9,12));var fields=new LinkedHashMap<String,String>();int count=0,total=0;
                for(String row;(row=line(in,8192,deadline)).length()>0;){if(++count>100||(total+=row.length())>32768)throw new IOException("WEB_HTTP_HEADERS_LIMIT");int colon=row.indexOf(':');if(colon<1)throw new IOException("WEB_HTTP_HEADER_INVALID");String name=row.substring(0,colon).strip().toLowerCase(Locale.ROOT),value=row.substring(colon+1).strip();if(fields.putIfAbsent(name,value)!=null&&Set.of("content-length","transfer-encoding","location","content-encoding").contains(name))throw new IOException("WEB_HTTP_HEADER_DUPLICATE");}
                if(Set.of(301,302,303,307,308).contains(code))return new Response(code,fields,new byte[0]);
                var out=new ByteArrayOutputStream();String transfer=fields.getOrDefault("transfer-encoding","");
                if(!transfer.isEmpty()){
                    if(fields.containsKey("content-length"))throw new IOException("WEB_AMBIGUOUS_BODY_LENGTH");
                    if(!transfer.equalsIgnoreCase("chunked"))throw new IOException("WEB_TRANSFER_ENCODING_UNSUPPORTED");
                    while(true){remaining(deadline);String size=line(in,128,deadline).split(";",2)[0].strip();if(!size.matches("[a-fA-F0-9]{1,8}"))throw new IOException("WEB_CHUNK_SIZE_INVALID");long n=Long.parseLong(size,16);if(n==0)break;if(n>maxBytes-out.size())throw new IOException("WEB_RESPONSE_TOO_LARGE");copy(in,out,(int)n,deadline);if(!line(in,2,deadline).isEmpty())throw new IOException("WEB_CHUNK_END_INVALID");}
                }else if(fields.containsKey("content-length")){
                    long n;try{n=Long.parseLong(fields.get("content-length"));}catch(NumberFormatException e){throw new IOException("WEB_CONTENT_LENGTH_INVALID");}if(n<0||n>maxBytes)throw new IOException("WEB_RESPONSE_TOO_LARGE");copy(in,out,(int)n,deadline);
                }else{
                    byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))!=-1;){remaining(deadline);if(out.size()+n>maxBytes)throw new IOException("WEB_RESPONSE_TOO_LARGE");out.write(buffer,0,n);}
                }
                return new Response(code,fields,out.toByteArray());
            }
            }finally{expiry.cancel(false);}
        }
    }
    private static int remaining(long deadline)throws IOException{long ms=(deadline-System.nanoTime())/1_000_000;if(ms<1)throw new IOException("WEB_REQUEST_TIMEOUT");return (int)Math.min(Integer.MAX_VALUE,ms);}
    private static String line(InputStream in,int max,long deadline)throws IOException{var out=new ByteArrayOutputStream();while(true){remaining(deadline);int b=in.read();if(b<0)throw new EOFException("WEB_RESPONSE_INCOMPLETE");if(b=='\n'){byte[] value=out.toByteArray();if(value.length==0||value[value.length-1]!='\r')throw new IOException("WEB_HTTP_LINE_INVALID");return new String(value,0,value.length-1,StandardCharsets.ISO_8859_1);}if(out.size()>max)throw new IOException("WEB_HTTP_LINE_TOO_LONG");out.write(b);}}
    private static void copy(InputStream in,ByteArrayOutputStream out,int length,long deadline)throws IOException{byte[] buffer=new byte[8192];for(int left=length;left>0;){remaining(deadline);int n=in.read(buffer,0,Math.min(left,buffer.length));if(n<0)throw new EOFException("WEB_RESPONSE_INCOMPLETE");out.write(buffer,0,n);left-=n;}}
    private static byte[] readBounded(InputStream in,int max)throws IOException{byte[] data=in.readNBytes(max+1);if(data.length>max)throw new IOException("WEB_RESPONSE_TOO_LARGE");return data;}
}
