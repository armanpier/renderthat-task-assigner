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
                    var rect = el.getBoundingClientRect();
                    if ((rect.width > 0 && rect.height > 0) || el.offsetParent !== null) {
                        return el;
                    }
                }
            }
            return null;
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
                // Do not use table row count as fallback with Math.max
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
     * JavaScript action script to click only the specific 'Assign me a Task' button.
     */
    val CLICK_SCRIPT: String = """
        (function() {
            $DOM_HELPERS_JS

            try {
                var assignBtn = findAssignButton();
                if (assignBtn) {
                    assignBtn.click();
                    return true;
                }
                return false;
            } catch (err) {
                return false;
            }
        })();
    """.trimIndent()

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
            // Unquote if Android WebView returns a stringified JSON string (e.g. "\"{\\\"isLoggedIn\\\"...}\"")
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
