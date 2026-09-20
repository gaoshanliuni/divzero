package dev.mineagent.runtime.worker.provider;
/** Public tool data schema; caller/actor/permission identity is supplied by the server, never the model. */
public final class ObjectDirectoryToolSchemas {
    private ObjectDirectoryToolSchemas(){}
    public static final String QUERY="""
        {"type":"object","properties":{
          "kind":{"enum":["PLAYER","TEAM","DIMENSION","ENTITY","INSTANCE"]},
          "name":{"type":"string","maxLength":128},"match":{"enum":["EXACT","PREFIX"]},
          "ids":{"type":"array","maxItems":64,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":128}},
          "team":{"type":"string","maxLength":128},"dimension":{"type":"string","maxLength":128},
          "near":{"oneOf":[
            {"type":"object","properties":{"reference":{"const":"ACTOR"},"radius":{"type":"number","minimum":1,"maximum":128}},"required":["reference","radius"],"additionalProperties":false},
            {"type":"object","properties":{"reference":{"const":"POSITION"},"radius":{"type":"number","minimum":1,"maximum":128},"position":{"type":"object","properties":{"dimension":{"type":"string"},"x":{"type":"number"},"y":{"type":"number"},"z":{"type":"number"}},"required":["dimension","x","y","z"],"additionalProperties":false}},"required":["reference","radius","position"],"additionalProperties":false}
          ]},
          "region":{"type":"object","properties":{"minX":{"type":"number"},"minY":{"type":"number"},"minZ":{"type":"number"},"maxX":{"type":"number"},"maxY":{"type":"number"},"maxZ":{"type":"number"}},"required":["minX","minY","minZ","maxX","maxY","maxZ"],"additionalProperties":false},
          "limit":{"type":"integer","minimum":1,"maximum":32},"cursor":{"type":"string","maxLength":128}
        },"required":["kind"],"dependentRequired":{"region":["dimension"]},"not":{"required":["near","region"]},"additionalProperties":false}
        """;
    public static final String REVALIDATE="{\"type\":\"object\",\"properties\":{\"query\":"+QUERY.replace("\"cursor\":{\"type\":\"string\",\"maxLength\":128}","\"cursor\":{\"type\":\"string\",\"maxLength\":0}")+"""
        ,"refs":{"type":"array","minItems":1,"maxItems":32,"items":{"type":"object","properties":{"worldId":{"type":"string","format":"uuid"},"kind":{"enum":["PLAYER","TEAM","DIMENSION","ENTITY","INSTANCE"]},"id":{"type":"string","maxLength":128},"generation":{"type":"string","format":"uuid"},"revision":{"type":"integer","minimum":0}},"required":["worldId","kind","id","generation","revision"],"additionalProperties":false}}},"required":["query","refs"],"additionalProperties":false}
        """;
}
