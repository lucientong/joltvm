/**
 * JoltVM Web IDE — ClassLoaders Panel
 * Copyright 2026 lucientong. Apache License 2.0
 */
const ClassLoadersPanel = (() => {
    'use strict';

    let treeData = null;

    async function loadTree() {
        const container = document.getElementById('classloaderTreeContent');
        container.innerHTML = '<div class="placeholder-msg">Loading ClassLoader tree...</div>';
        const res = await JoltAPI.classloaderTree();
        if (!res.ok) {
            container.innerHTML = '<div class="error-msg">Failed to load ClassLoader tree</div>';
            return;
        }
        treeData = res.data;
        document.getElementById('classloaderTotalCount').textContent = `${res.data.totalLoaders} ClassLoaders`;
        renderTree(container, res.data.tree, 0);
    }

    function renderTree(container, nodes, depth) {
        container.innerHTML = '';
        const ul = document.createElement('ul');
        ul.className = 'cl-tree';
        for (const node of nodes) {
            const li = document.createElement('li');
            li.style.paddingLeft = (depth * 20) + 'px';

            const header = document.createElement('div');
            header.className = 'cl-node';
            header.innerHTML = `
                <span class="cl-toggle">${node.children && node.children.length > 0 ? '&#9660;' : '&#9679;'}</span>
                <span class="cl-name" title="${node.className}">${escapeHtml(node.name)}</span>
                <span class="cl-count badge">${node.classCount} classes</span>
                <button class="btn btn-sm cl-browse" data-id="${node.id}">Browse</button>
            `;
            li.appendChild(header);

            if (node.children && node.children.length > 0) {
                const childContainer = document.createElement('div');
                childContainer.className = 'cl-children';
                renderTree(childContainer, node.children, depth + 1);
                li.appendChild(childContainer);

                header.querySelector('.cl-toggle').addEventListener('click', () => {
                    childContainer.classList.toggle('collapsed');
                    const toggle = header.querySelector('.cl-toggle');
                    toggle.innerHTML = childContainer.classList.contains('collapsed') ? '&#9654;' : '&#9660;';
                });
            }

            header.querySelector('.cl-browse').addEventListener('click', () => {
                loadClasses(node.id, node.name);
            });

            ul.appendChild(li);
        }
        container.appendChild(ul);
    }

    async function loadClasses(loaderId, loaderName) {
        const panel = document.getElementById('classloaderClassesPanel');
        panel.innerHTML = '<div class="placeholder-msg">Loading classes...</div>';

        const search = document.getElementById('classloaderClassSearch').value.trim();
        const res = await JoltAPI.classloaderClasses(loaderId, 0, 200, search || undefined);
        if (!res.ok) {
            panel.innerHTML = '<div class="error-msg">Failed to load classes</div>';
            return;
        }

        const data = res.data;
        let html = `<h4>${escapeHtml(loaderName)} — ${data.totalCount} classes (page ${data.page + 1}/${data.totalPages || 1})</h4>`;
        html += '<ul class="cl-class-list">';
        for (const cls of data.classes) {
            html += `<li>${escapeHtml(cls)}</li>`;
        }
        html += '</ul>';
        panel.innerHTML = html;
    }

    async function loadConflicts() {
        const container = document.getElementById('classloaderConflictsContent');
        container.innerHTML = '<div class="placeholder-msg">Checking for conflicts...</div>';
        const res = await JoltAPI.classloaderConflicts();
        if (!res.ok) {
            container.innerHTML = '<div class="error-msg">Failed to detect conflicts</div>';
            return;
        }

        const data = res.data;
        if (data.count === 0) {
            container.innerHTML = '<div class="success-msg">No class conflicts detected.</div>';
            return;
        }

        let html = `<div class="warning-msg">${data.count} class conflict(s) found</div>`;
        html += '<table class="data-table"><thead><tr><th>Class Name</th><th>Loaded By</th></tr></thead><tbody>';
        for (const c of data.conflicts) {
            html += `<tr><td>${escapeHtml(c.className)}</td><td>${c.loaders.join(', ')}</td></tr>`;
        }
        html += '</tbody></table>';
        container.innerHTML = html;
    }

    function escapeHtml(text) {
        const el = document.createElement('span');
        el.textContent = text;
        return el.innerHTML;
    }

    function init() {
        document.getElementById('classloaderRefresh')?.addEventListener('click', loadTree);
        document.getElementById('classloaderConflictBtn')?.addEventListener('click', loadConflicts);
        document.getElementById('classloaderClassSearch')?.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') loadTree();
        });
    }

    return { init, loadTree, loadConflicts };
})();

// Auto-init on DOM ready
document.addEventListener('DOMContentLoaded', () => ClassLoadersPanel.init());
