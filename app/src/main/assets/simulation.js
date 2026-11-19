(function() {
    let availableQueue = 0;
    let assignedTasks = [];
    let isButtonManuallyHidden = false;

    const assignBtn = document.getElementById('btn-assign-task');
    const tableBody = document.getElementById('rt-table-body');
    const emptyMsg = document.getElementById('no-records-msg');
    const recordsText = document.getElementById('records-counter-text');
    const simStatus = document.getElementById('sim-status');
    const toastContainer = document.getElementById('toast-container');

    function updateUI() {
        const count = assignedTasks.length;
        recordsText.textContent = `Number of records: ${count}`;
        simStatus.textContent = `Queue: ${availableQueue} available | Table: ${count} assigned`;

        // Render table rows
        if (count === 0) {
            tableBody.innerHTML = '';
            emptyMsg.style.display = 'block';
        } else {
            emptyMsg.style.display = 'none';
            tableBody.innerHTML = assignedTasks.map((task, idx) => `
                <tr>
                    <td><input type="checkbox" id="check-${idx}"></td>
                    <td><strong>${task.name}</strong> (${task.sku})</td>
                    <td>${task.createdDate}</td>
                </tr>
            `).join('');
        }

        // Button visibility rule: If 5 tasks assigned or manually hidden, button disappears
        if (count >= 5 || isButtonManuallyHidden) {
            assignBtn.style.display = 'none';
        } else {
            assignBtn.style.display = 'inline-block';
        }
    }

    function showToast(message, type = 'success') {
        const toast = document.createElement('div');
        toast.className = `toast ${type === 'warning' ? 'warning' : ''}`;
        toast.textContent = message;
        toastContainer.appendChild(toast);
        setTimeout(() => {
            toast.remove();
        }, 3000);
    }

    // Action: Assign me a Task
    assignBtn.addEventListener('click', function() {
        if (availableQueue > 0) {
            availableQueue--;
            const newIndex = assignedTasks.length + 1;
            const now = new Date();
            const dateStr = now.toLocaleDateString() + ' ' + now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            
            const task = {
                id: 1000 + newIndex,
                name: `Lidl Food Package (Shading) #${1040 + newIndex}`,
                sku: `SKU-${9000 + newIndex}`,
                createdDate: dateStr
            };
            assignedTasks.push(task);
            showToast(`🎉 Success: Assigned task #${task.id}!`, 'success');
            updateUI();
        } else {
            showToast(`ℹ️ No tasks currently available in queue.`, 'warning');
            updateUI();
        }
    });

    // Simulator Controls
    document.getElementById('sim-drop-3').addEventListener('click', function() {
        availableQueue += 3;
        showToast(`📦 Dropped 3 tasks into pipeline queue!`, 'success');
        updateUI();
    });

    document.getElementById('sim-drop-5').addEventListener('click', function() {
        availableQueue += 5;
        showToast(`📦 Dropped 5 tasks into pipeline queue!`, 'success');
        updateUI();
    });

    document.getElementById('sim-toggle-btn').addEventListener('click', function() {
        isButtonManuallyHidden = !isButtonManuallyHidden;
        showToast(`Button visibility toggled: ${isButtonManuallyHidden ? 'Hidden' : 'Visible'}`, 'warning');
        updateUI();
    });

    document.getElementById('sim-reset').addEventListener('click', function() {
        availableQueue = 0;
        assignedTasks = [];
        isButtonManuallyHidden = false;
        showToast(`🔄 Simulator reset to 0 tasks.`, 'warning');
        updateUI();
    });

    updateUI();
})();
