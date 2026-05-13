marked.use({
    renderer: {
        link(hrefObj, title, text) {
            const token = typeof hrefObj === 'object' ? hrefObj : null;
            const href = token ? token.href : hrefObj;
            const t = token ? token.title : title;
            let txt = text;
            if (token) {
                if (token.tokens) {
                    txt = this.parser.parseInline(token.tokens);
                } else if (token.text) {
                    txt = token.text;
                } else {
                    txt = href;
                }
            }
            let out = `<a href="${href}" target="_blank" rel="noopener noreferrer"`;
            if (t) {
                out += ` title="${t}"`;
            }
            out += `>${txt}</a>`;
            return out;
        }
    }
});

async function sendMessage() {
    const inputField = document.getElementById('message-input');
    const message = inputField.value.trim();
    if (!message) return;

    appendMessage('You', message, 'user');
    inputField.value = '';

    showSpinner();

    try {
        const response = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message: message })
        });

        if (response.ok) {
            hideSpinner();
            const data = await response.json();
            appendMessage('Bot', data.answer, 'bot');

            // Check if RAG had hits. If not, show a warning.
            if (data.sources && data.sources.length > 0) {
                appendSources(data.sources);
            } else {
                appendWarning();
            }
        } else {
            hideSpinner();
            appendMessage('Error', 'Failed to get response from server.', 'bot');
        }
    } catch (error) {
        hideSpinner();
        appendMessage('Error', 'An error occurred.', 'bot');
    }
}

// Add this new function to append a UI warning
function appendWarning() {
    const chatBox = document.getElementById('chat-box');
    const warningDiv = document.createElement('div');
    warningDiv.className = 'alert alert-warning ms-4 mb-3 p-2 small border rounded';
    warningDiv.innerHTML = '<strong>⚠️ Warning:</strong> This answer is not based on STTRS Knowledge (no internal documentation was found for this query). It was generated using the AI\'s general knowledge and may be incorrect or hallucinated.';
    chatBox.appendChild(warningDiv);
    chatBox.scrollTop = chatBox.scrollHeight;
}

function showSpinner() {
    const chatBox = document.getElementById('chat-box');
    const msgDiv = document.createElement('div');
    msgDiv.id = 'loading-spinner';
    msgDiv.className = 'd-flex mb-3 justify-content-start';
    msgDiv.innerHTML = `
        <div class="p-3 rounded-3 shadow-sm bg-light text-dark border">
            <div class="spinner-border spinner-border-sm text-primary" role="status">
                <span class="visually-hidden">Loading...</span>
            </div>
            <span class="ms-2">Thinking...</span>
        </div>
    `;
    chatBox.appendChild(msgDiv);
    chatBox.scrollTop = chatBox.scrollHeight;
}

function hideSpinner() {
    const spinner = document.getElementById('loading-spinner');
    if (spinner) spinner.remove();
}

function appendSources(sources) {
    const chatBox = document.getElementById('chat-box');
    const sourceBox = document.createElement('details');
    sourceBox.className = 'message source-box ms-4 p-2 mb-3 bg-light border rounded text-muted';

    const title = document.createElement('summary');
    title.className = 'source-title fw-bold';
    title.style.cursor = 'pointer';
    title.textContent = 'RAG Context Information:';
    sourceBox.appendChild(title);

    sources.forEach((sourceObj, index) => {
        const itemDiv = document.createElement('div');
        itemDiv.className = 'source-item mb-4 p-3 bg-white border rounded shadow-sm';

        // Add Headline with Link and Score Badge
        const headerDiv = document.createElement('div');
        headerDiv.className = 'mb-2 d-flex justify-content-between align-items-start';

        const displayTitle = sourceObj.titlePath || sourceObj.title || `Source ${index + 1}`;
        const url = sourceObj.url || '#';

        const titleSpan = document.createElement('span');
        if (sourceObj.url) {
            titleSpan.innerHTML = `<strong><a href="${url}" target="_blank" rel="noopener noreferrer" class="text-primary text-decoration-none">${displayTitle}</a></strong>`;
        } else {
            titleSpan.innerHTML = `<strong class="text-dark">${displayTitle}</strong>`;
        }

        headerDiv.appendChild(titleSpan);

        if (sourceObj.score != null) {
            const scoreBadge = document.createElement('span');
            scoreBadge.className = 'badge bg-info text-dark rounded-pill border';
            scoreBadge.textContent = 'Score: ' + sourceObj.score.toFixed(3);
            headerDiv.appendChild(scoreBadge);
        }

        itemDiv.appendChild(headerDiv);

        const contentDiv = document.createElement('div');
        contentDiv.className = 'small text-break source-content text-dark';

        // Parse the Markdown using marked
        contentDiv.innerHTML = marked.parse(sourceObj.text || '');

        itemDiv.appendChild(contentDiv);

        sourceBox.appendChild(itemDiv);
    });

    chatBox.appendChild(sourceBox);
    chatBox.scrollTop = chatBox.scrollHeight;
}

function appendMessage(sender, text, className) {
    const chatBox = document.getElementById('chat-box');
    const msgDiv = document.createElement('div');

    const isUser = className === 'user';
    msgDiv.className = `d-flex mb-3 ${isUser ? 'justify-content-end' : 'justify-content-start'}`;

    const contentWrapper = document.createElement('div');
    contentWrapper.className = `p-3 rounded-3 shadow-sm ${isUser ? 'bg-primary text-white' : 'bg-light text-dark border'}`;
    contentWrapper.style.maxWidth = '75%';

    const senderSpan = document.createElement('div');
    senderSpan.className = 'fw-bold small mb-1';
    senderSpan.textContent = sender;

    const contentSpan = document.createElement('div');
    contentSpan.className = 'message-content';
    contentSpan.innerHTML = marked.parse(text);

    contentWrapper.appendChild(senderSpan);
    contentWrapper.appendChild(contentSpan);
    msgDiv.appendChild(contentWrapper);

    chatBox.appendChild(msgDiv);
    chatBox.scrollTop = chatBox.scrollHeight;
}

function handleKeyPress(event) {
    if (event.key === 'Enter') {
        sendMessage();
    }
}
