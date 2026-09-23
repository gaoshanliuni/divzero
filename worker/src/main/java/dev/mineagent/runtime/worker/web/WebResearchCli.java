package dev.mineagent.runtime.worker.web;
/** Optional local read-only CLI. Does not call a model or load Provider credentials. */
public final class WebResearchCli {
    public static void main(String[] args)throws Exception{
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        try{
            if(args.length!=2)throw new IllegalArgumentException("WEB_USAGE");
            var service=new WebResearchService();Object result=switch(args[0]){case "search"->service.search(args[1]);case "web"->service.search(args[1],"web");case "buildings"->service.search(args[1],"buildings");case "read"->service.read(args[1]);default->throw new IllegalArgumentException("WEB_ACTION");};
            System.out.write(json.writeValueAsBytes(result));System.out.write(10);
        }catch(Exception failure){String code=java.util.Objects.toString(failure.getMessage(),"");System.out.write(json.writeValueAsBytes(java.util.Map.of("status","FAILED","error",code.matches("WEB_[A-Z0-9_]{1,80}")?code:"WEB_REQUEST_FAILED")));System.out.write(10);System.exit(1);}
    }
}
