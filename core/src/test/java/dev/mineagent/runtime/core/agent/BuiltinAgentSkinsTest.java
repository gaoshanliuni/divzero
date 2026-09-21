package dev.mineagent.runtime.core.agent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BuiltinAgentSkinsTest {@Test void catalogOnlyContainsActualBundledSkinsOrCopyPlayer(){assertEquals(19,BuiltinAgentSkins.catalog().size());for(var v:BuiltinAgentSkins.catalog())assertTrue(BuiltinAgentSkins.valid(v.get("id")));assertFalse(BuiltinAgentSkins.valid("https://secret.invalid/key"));assertFalse(BuiltinAgentSkins.valid("../alex:wide"));assertFalse(BuiltinAgentSkins.valid("alex:wrong"));}}
