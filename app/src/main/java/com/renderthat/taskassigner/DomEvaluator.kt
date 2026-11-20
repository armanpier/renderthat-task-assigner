package com.renderthat.taskassigner

import org.json.JSONObject

data class DomInspectionResult(
    val isLoggedIn: Boolean,
    val isLoading: Boolean,
    val filtersAdjusted: Boolean,
    val taskCount: Int,
    val buttonAvailable: Boolean,
    val rawText: String
)

object DomEvaluator {

    /**
     * Shared JavaScript functions for traversing Shadow DOM and locating elements.
     */
    private const val DOM_HELPERS_JS: String = """
        function getAllRoots(root) {
            root = root || document;
            var roots = [root];
            function collect(current) {
                try {
                    var walker = document.createTreeWalker(current, 1 /* NodeFilter.SHOW_ELEMENT */, null, false);
                    var n;
                    while ((n = walker.nextNode())) {
                        if (n.shadowRoot && roots.indexOf(n.shadowRoot) === -1) {
                            roots.push(n.shadowRoot);
                            collect(n.shadowRoot);
                        }
                    }
                } catch (e) {
                    var elements = current.querySelectorAll ? current.querySelectorAll('*') : [];
                    for (var i = 0; i < elements.length; i++) {
                        var el = elements[i];
                        if (el.shadowRoot && roots.indexOf(el.shadowRoot) === -1) {
                            roots.push(el.shadowRoot);
                            collect(el.shadowRoot);
                        }
                    }
                }
            }
            collect(root);
            return roots;
        }

        function queryAllDeep(selector, root) {
            root = root || document;
            var roots = getAllRoots(root);
            var seen = [];
            var results = [];
            for (var r = 0; r < roots.length; r++) {
                try {
                    var list = roots[r].querySelectorAll(selector);
                    for (var i = 0; i < list.length; i++) {
                        var item = list[i];
                        if (seen.indexOf(item) === -1) {
                            seen.push(item);
                            results.push(item);
                        }
                    }
                } catch (_) {}
            }
            return results;
        }

        function findAssignButton() {
            var direct = document.getElementById('btn-assign-task');
            if (direct && !direct.disabled) {
                var dStyle = window.getComputedStyle(direct);
                if (dStyle.display !== 'none' && dStyle.visibility !== 'hidden') {
                    return direct;
                }
            }

            var clickables = queryAllDeep('button, [role="button"], a.btn, input[type="button"], input[type="submit"]');
            for (var i = 0; i < clickables.length; i++) {
                var el = clickables[i];
                var text = (el.textContent || el.value || el.innerText || '').trim().toLowerCase();
                if (text === 'assign me a task') {
                    if (el.disabled || el.getAttribute('aria-disabled') === 'true') {
                        continue;
                    }
                    var style = window.getComputedStyle(el);
                    if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') {
                        continue;
                    }
                    return el;
                }
            }
            return null;
        }

        function simulateHumanClick(el) {
            if (!el) return false;
            try {
                var rect = el.getBoundingClientRect();
                var cx = (rect.left || 100) + Math.max(5, (rect.width || 60) * (0.2 + Math.random() * 0.6));
                var cy = (rect.top || 100) + Math.max(5, (rect.height || 30) * (0.2 + Math.random() * 0.6));
                var init = {
                    bubbles: true,
                    cancelable: true,
                    view: window,
                    clientX: cx,
                    clientY: cy,
                    screenX: cx + 20,
                    screenY: cy + 80,
                    buttons: 1
                };
                try { el.dispatchEvent(new PointerEvent('pointerover', init)); } catch (_) {}
                try { el.dispatchEvent(new MouseEvent('mouseover', init)); } catch (_) {}
                try { el.dispatchEvent(new PointerEvent('pointerdown', init)); } catch (_) {}
                try { el.dispatchEvent(new MouseEvent('mousedown', init)); } catch (_) {}
                try { el.focus(); } catch (_) {}
                try { el.dispatchEvent(new PointerEvent('pointerup', init)); } catch (_) {}
                try { el.dispatchEvent(new MouseEvent('mouseup', init)); } catch (_) {}
                el.click();
                return true;
            } catch (err) {
                try { el.click(); return true; } catch (_) { return false; }
            }
        }
    """

    /**
     * JavaScript inspection script that traverses Shadow DOM, checks authentication,
     * inspects loading spinners, enforces cascading filters, reads exact task count,
     * and checks for the 'Assign me a Task' button.
     */
    val INSPECTION_SCRIPT: String = """
        (function() {
            $DOM_HELPERS_JS

            try {
                // 1. Session check: Detect if on login page (password fields present on live site)
                var isSimulation = window.location.href.indexOf('simulation.html') !== -1;
                var isLoggedIn = true;
                if (!isSimulation) {
                    var passwordInputs = queryAllDeep('input[type="password"]');
                    if (passwordInputs.length > 0) {
                        isLoggedIn = false;
                    }
                }

                // 2. Loading Spinner check: Detect lightning-spinner, .slds-spinner, or aria-busy elements
                var spinnerSelectors = 'lightning-spinner, .slds-spinner, .slds-spinner_container, [aria-busy="true"], [role="progressbar"]';
                var spinners = queryAllDeep(spinnerSelectors);
                var isLoading = spinners.some(function(el) {
                    if (el.getAttribute && el.getAttribute('aria-busy') === 'true') {
                        return true;
                    }
                    var style = window.getComputedStyle(el);
                    if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') {
                        return false;
                    }
                    var rect = el.getBoundingClientRect();
                    return (rect.width > 0 && rect.height > 0) || el.offsetParent !== null;
                });

                // 3. Cascading Filter check and enforcement
                var filtersAdjusted = false;
                if (isLoggedIn && !isLoading) {
                    function findFilterSelect(filterKey) {
                        var selects = queryAllDeep('select');
                        var key = filterKey.toLowerCase();
                        
                        // Check direct attributes
                        for (var i = 0; i < selects.length; i++) {
                            var sel = selects[i];
                            var id = (sel.id || '').toLowerCase();
                            var name = (sel.getAttribute('name') || '').toLowerCase();
                            var ariaLabel = (sel.getAttribute('aria-label') || '').toLowerCase();
                            var dataId = (sel.getAttribute('data-id') || '').toLowerCase();
                            if (id.indexOf(key) !== -1 || name.indexOf(key) !== -1 || ariaLabel.indexOf(key) !== -1 || dataId.indexOf(key) !== -1) {
                                return sel;
                            }
                        }
                        
                        // Check enclosing lightning-select or container
                        for (var j = 0; j < selects.length; j++) {
                            var sel2 = selects[j];
                            var lgt = sel2.closest ? (sel2.closest('lightning-select') || sel2.closest('lightning-combobox') || sel2.closest('.filter-item')) : null;
                            if (lgt) {
                                var labelAttr = (lgt.getAttribute('label') || '').toLowerCase();
                                var nameAttr = (lgt.getAttribute('name') || '').toLowerCase();
                                if (labelAttr.indexOf(key) !== -1 || nameAttr.indexOf(key) !== -1) {
                                    return sel2;
                                }
                                var labelEl = lgt.querySelector('label');
                                if (labelEl && (labelEl.textContent || '').toLowerCase().indexOf(key) !== -1) {
                                    return sel2;
                                }
                            }
                            if (sel2.id) {
                                var lbl = document.querySelector('label[for="' + sel2.id + '"]');
                                if (lbl && (lbl.textContent || '').toLowerCase().indexOf(key) !== -1) {
                                    return sel2;
                                }
                            }
                            if (sel2.parentElement) {
                                var lbl2 = sel2.parentElement.querySelector('label');
                                if (lbl2 && (lbl2.textContent || '').toLowerCase().indexOf(key) !== -1) {
                                    return sel2;
                                }
                            }
                        }
                        return null;
                    }

                    function enforceFilter(filterKey, expectedSubstr) {
                        var sel = findFilterSelect(filterKey);
                        if (!sel || !sel.options || sel.options.length === 0) return false;

                        var currentIdx = sel.selectedIndex >= 0 ? sel.selectedIndex : 0;
                        var currentOption = sel.options[currentIdx];
                        var currentText = (currentOption ? (currentOption.text || currentOption.value || '') : '').toLowerCase();
                        var expected = expectedSubstr.toLowerCase();

                        if (currentText.indexOf(expected) === -1) {
                            for (var k = 0; k < sel.options.length; k++) {
                                var opt = sel.options[k];
                                var optText = (opt.text || opt.value || '').toLowerCase();
                                if (optText.indexOf(expected) !== -1) {
                                    sel.selectedIndex = k;
                                    sel.value = opt.value;
                                    sel.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
                                    sel.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
                                    return true;
                                }
                            }
                        }
                        return false;
                    }

                    // Enforce Show: "My", Account: "lidl", Quote: "Food", Team: "Shading"
                    if (enforceFilter('show', 'my')) filtersAdjusted = true;
                    if (enforceFilter('account', 'lidl')) filtersAdjusted = true;
                    if (enforceFilter('quote', 'food')) filtersAdjusted = true;
                    if (enforceFilter('team', 'shading')) filtersAdjusted = true;
                }

                // 4. Exact Task Count Parser: 'Number of records: X'
                function extractTaskCount() {
                    var bodyText = document.body ? (document.body.innerText || '') : '';
                    var match = bodyText.match(/Number of records:\s*(\d+)/i);
                    if (match) {
                        return parseInt(match[1], 10);
                    }
                    var allEls = queryAllDeep('*');
                    for (var m = 0; m < allEls.length; m++) {
                        var text = allEls[m].textContent || '';
                        if (text.indexOf('Number of records') !== -1) {
                            var subMatch = text.match(/Number of records:\s*(\d+)/i);
                            if (subMatch) {
                                return parseInt(subMatch[1], 10);
                            }
                        }
                    }
                    return 0;
                }
                var taskCount = extractTaskCount();

                // 5. Button Locator: Search for enabled, visible 'Assign me a Task'
                var assignBtn = findAssignButton();
                var isButtonAvailable = assignBtn !== null;

                return JSON.stringify({
                    isLoggedIn: isLoggedIn,
                    isLoading: isLoading,
                    filtersAdjusted: filtersAdjusted,
                    taskCount: taskCount,
                    buttonAvailable: isButtonAvailable
                });
            } catch (err) {
                return JSON.stringify({
                    isLoggedIn: true,
                    isLoading: false,
                    filtersAdjusted: false,
                    taskCount: 0,
                    buttonAvailable: false,
                    error: String(err)
                });
            }
        })();
    """.trimIndent()

    /**
     * JavaScript action script to click only the specific 'Assign me a Task' button
     * with anti-bot human pointer event simulation.
     */
    val CLICK_SCRIPT: String = """
        (function() {
            $DOM_HELPERS_JS

            try {
                var assignBtn = findAssignButton();
                if (assignBtn) {
                    return simulateHumanClick(assignBtn);
                }
                return false;
            } catch (err) {
                return false;
            }
        })();
    """.trimIndent()

    /**
     * Off-hours Keep-Alive heartbeat script that performs subtle mousemove/scroll events
     * and light background fetch pings so the portal server never logs out the user due to idle inactivity.
     */
    val KEEP_ALIVE_SCRIPT: String = """
        (function() {
            $DOM_HELPERS_JS

            try {
                // 1. Synthetic human-like mouse movement
                var cx = Math.floor(Math.random() * 400) + 50;
                var cy = Math.floor(Math.random() * 300) + 50;
                var evt = new MouseEvent('mousemove', {
                    view: window,
                    bubbles: true,
                    cancelable: true,
                    clientX: cx,
                    clientY: cy
                });
                document.dispatchEvent(evt);
                window.dispatchEvent(new Event('scroll'));

                // 2. Head request ping to maintain session
                try {
                    fetch(window.location.href, { method: 'HEAD', cache: 'no-store' });
                } catch (_) {}

                // 3. Check login status
                var isSimulation = window.location.href.indexOf('simulation.html') !== -1;
                var isLoggedIn = true;
                if (!isSimulation) {
                    var passInputs = queryAllDeep('input[type="password"]');
                    if (passInputs.length > 0) {
                        isLoggedIn = false;
                    }
                }

                return JSON.stringify({
                    alive: true,
                    isLoggedIn: isLoggedIn,
                    url: window.location.href,
                    timestamp: Date.now()
                });
            } catch (err) {
                return JSON.stringify({
                    alive: false,
                    isLoggedIn: true,
                    error: String(err)
                });
            }
        })();
    """.trimIndent()

    /**
     * Generates a JavaScript script that automatically populates saved portal credentials
     * into login fields and submits the form so the user doesn't have to re-type them.
     */
    fun buildAutoLoginScript(username: String, password: String, autoSubmit: Boolean = true): String {
        val safeUser = escapeJs(username)
        val safePass = escapeJs(password)
        return """
            (function() {
                $DOM_HELPERS_JS

                try {
                    var userVal = "$safeUser";
                    var passVal = "$safePass";
                    var shouldSubmit = $autoSubmit;

                    if (!userVal && !passVal) {
                        return JSON.stringify({ handled: false, reason: 'Empty credentials' });
                    }

                    // Find username field
                    var userCandidates = queryAllDeep('input[type="email"], input#username, input[name="username"], input[autocomplete="username"], input[type="text"]');
                    var userInput = null;
                    for (var i = 0; i < userCandidates.length; i++) {
                        var inp = userCandidates[i];
                        var t = (inp.type || '').toLowerCase();
                        if (t !== 'hidden' && t !== 'submit' && t !== 'button' && t !== 'checkbox' && t !== 'radio') {
                            userInput = inp;
                            break;
                        }
                    }

                    // Find password field
                    var passCandidates = queryAllDeep('input[type="password"]');
                    var passInput = passCandidates.length > 0 ? passCandidates[0] : null;

                    var filledUser = false;
                    var filledPass = false;

                    if (userInput && userVal) {
                        userInput.value = userVal;
                        userInput.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
                        userInput.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
                        filledUser = true;
                    }

                    if (passInput && passVal) {
                        passInput.value = passVal;
                        passInput.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
                        passInput.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
                        filledPass = true;
                    }

                    var submitted = false;
                    if (shouldSubmit && filledUser && filledPass) {
                        var submitButtons = queryAllDeep('button[type="submit"], input[type="submit"], #Login, .sfdc_button, button');
                        for (var j = 0; j < submitButtons.length; j++) {
                            var btn = submitButtons[j];
                            var txt = (btn.textContent || btn.value || '').trim().toLowerCase();
                            var btnId = (btn.id || '').toLowerCase();
                            if (btnId === 'login' || txt.indexOf('log in') !== -1 || txt.indexOf('sign in') !== -1 || btn.type === 'submit') {
                                simulateHumanClick(btn);
                                submitted = true;
                                break;
                            }
                        }
                    }

                    return JSON.stringify({
                        handled: true,
                        filledUser: filledUser,
                        filledPass: filledPass,
                        submitted: submitted
                    });
                } catch (err) {
                    return JSON.stringify({ handled: false, error: String(err) });
                }
            })();
        """.trimIndent()
    }

    private fun escapeJs(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    fun parseResult(rawJson: String?): DomInspectionResult {
        if (rawJson.isNullOrBlank() || rawJson == "null") {
            return DomInspectionResult(
                isLoggedIn = false,
                isLoading = false,
                filtersAdjusted = false,
                taskCount = 0,
                buttonAvailable = false,
                rawText = ""
            )
        }

        return try {
            var cleaned = rawJson.trim()
            if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length > 2) {
                cleaned = cleaned.substring(1, cleaned.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            }
            val json = JSONObject(cleaned)
            DomInspectionResult(
                isLoggedIn = json.optBoolean("isLoggedIn", true),
                isLoading = json.optBoolean("isLoading", false),
                filtersAdjusted = json.optBoolean("filtersAdjusted", false),
                taskCount = json.optInt("taskCount", 0),
                buttonAvailable = json.optBoolean("buttonAvailable", false),
                rawText = cleaned
            )
        } catch (e: Exception) {
            DomInspectionResult(
                isLoggedIn = true,
                isLoading = false,
                filtersAdjusted = false,
                taskCount = 0,
                buttonAvailable = false,
                rawText = rawJson
            )
        }
    }
}
