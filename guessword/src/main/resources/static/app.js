window.onerror = function(message, source, lineno, colno, error) {
    showError('脚本错误：' + message + ' (行' + lineno + ')');
    return true;
};

let ws;
let playerId = null;
let currentRoom = null;
let myPlayer = null;
let swapMode = false;
let pendingSwapRequest = null;
let errorTimeout;
let confirmCallback = null;

const roomDisplay = document.getElementById('roomDisplay');
const stateDisplay = document.getElementById('stateDisplay');
const userInfo = document.getElementById('userInfo');
const timeDisplay = document.getElementById('timeDisplay');
const groupingView = document.getElementById('groupingView');
const gameView = document.getElementById('gameView');
const gameOverView = document.getElementById('gameOverView');
const boardEl = document.getElementById('board');
const teamsContainer = document.getElementById('teamsContainer');
const spectatorsContainer = document.getElementById('spectatorsContainer');
const errorToast = document.getElementById('errorToast');

function load_room(room) {
    if (!room || room === '0' || !/^\d{4}$/.test(room)) {
        location.href = '/guessword/entry.html';
        return;
    }
    const nickname = sessionStorage.getItem('nickname');
    if (!nickname) {
        location.href = '/guessword/entry.html';
        return;
    }
    if (!sessionStorage.getItem('clientId')) {
        sessionStorage.setItem('clientId', Date.now() + '-' + Math.random().toString(36).substr(2, 9));
    }
    window.clientId = sessionStorage.getItem('clientId');
    connect();
}

const room = sessionStorage.getItem('room');
load_room(room);

function connect() {
    const protocol = location.protocol === 'https:' ? 'wss://' : 'ws://';
    ws = new WebSocket(protocol + location.host + '/guessword/ws');
    ws.onopen = () => {
        send({
            type: 'join',
            roomId: room,
            nickname: sessionStorage.getItem('nickname'),
            clientId: window.clientId
        });
    };
    ws.onmessage = (e) => {
        try {
            handleMessage(JSON.parse(e.data));
        } catch (err) {
            console.error(err);
            showError('消息解析错误：' + err.message);
        }
    };
    ws.onclose = () => {
        setTimeout(connect, 2000);
    };
    ws.onerror = () => {
        ws.close();
    };
}

function send(obj) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(obj));
    }
}

function handleMessage(msg) {
    switch (msg.type) {
        case 'joined':
            playerId = msg.playerId;
            break;
        case 'room_state':
            currentRoom = msg;
            myPlayer = currentRoom.players.find(p => p.id === playerId);
            renderAll(currentRoom);
            break;
        case 'swap_request':
            showSwapRequest(msg);
            break;
        case 'pending_action_request':
            showPendingAction(msg);
            break;
        case 'room_dissolved':
            alert('房间已解散');
            location.href = '/guessword/entry.html';
            break;
        case 'left_room':
            location.href = '/guessword/entry.html';
            break;
        case 'error':
            showError(msg.message);
            break;
    }
}

function renderAll(state) {
    roomDisplay.textContent = state.roomId;
    stateDisplay.textContent = state.state;
    if (myPlayer) {
        const teamName = myPlayer.team ? myPlayer.team : '观战';
        const roleName = myPlayer.roleType === 'SPYMASTER' ? '描述者' : myPlayer.roleType === 'OPERATIVE' ? '指认者' : '';
        userInfo.textContent = `昵称：${myPlayer.nickname}，角色：${teamName}${roleName}`;
    } else {
        userInfo.textContent = '';
    }

    let times = [];
    if (state.state === 'PLAYING' || state.state === 'GAME_OVER') {
        times.push(`红:${formatTime(state.redElapsedSeconds)}`);
        times.push(`蓝:${formatTime(state.blueElapsedSeconds)}`);
        if (state.teamCount >= 3) times.push(`绿:${formatTime(state.greenElapsedSeconds)}`);
        if (state.teamCount >= 4) times.push(`紫:${formatTime(state.purpleElapsedSeconds)}`);
    }
    timeDisplay.textContent = times.join(' ');

    if (state.state === 'GROUPING') {
        groupingView.style.display = 'block';
        gameView.style.display = 'none';
        gameOverView.style.display = 'none';
        renderGrouping(state);
    } else if (state.state === 'PLAYING') {
        groupingView.style.display = 'none';
        gameView.style.display = 'block';
        gameOverView.style.display = 'none';
        renderGame(state);
    } else if (state.state === 'GAME_OVER') {
        groupingView.style.display = 'none';
        gameView.style.display = 'none';
        gameOverView.style.display = 'block';
        renderGameOver(state);
    }
}

function renderGrouping(state) {
    const configDiv = document.getElementById('configDisplay');
    const civilianCount = state.civilianCount !== undefined ? state.civilianCount : (state.rows * state.cols - state.teamCardCount * state.teamCount - state.blackCount);
    configDiv.innerHTML = `
        行数：${state.rows}，列数：${state.cols}，每队猜词数：${state.teamCardCount}，杀手数：${state.blackCount}，队伍数：${state.teamCount}，平民数：${civilianCount}
    `;
    teamsContainer.innerHTML = '';
    spectatorsContainer.innerHTML = '';
    const teamNames = ['RED', 'BLUE', 'GREEN', 'PURPLE'];
    for (let t = 0; t < state.teamCount; t++) {
        const teamName = teamNames[t];
        const teamDiv = document.createElement('div');
        teamDiv.className = 'team';
        teamDiv.innerHTML = `<h3>${teamName}队</h3>`;
        const playersDiv = document.createElement('div');
        playersDiv.className = 'players';
        state.players.filter(p => p.team === teamName && !p.spectator).forEach(p => {
            playersDiv.appendChild(createPlayerCard(p, state));
        });
        teamDiv.appendChild(playersDiv);
        teamsContainer.appendChild(teamDiv);
    }
    const specDiv = document.createElement('div');
    specDiv.className = 'team';
    specDiv.innerHTML = '<h3>观战者</h3><div class="players"></div>';
    const specPlayersDiv = specDiv.querySelector('.players');
    state.players.filter(p => p.spectator).forEach(p => {
        specPlayersDiv.appendChild(createPlayerCard(p, state));
    });
    if (state.players.some(p => p.spectator)) spectatorsContainer.appendChild(specDiv);

    const readyBtn = document.getElementById('readyBtn');
    if (myPlayer && !myPlayer.spectator) {
        readyBtn.style.display = 'inline-block';
        const ready = myPlayer.ready;
        readyBtn.textContent = ready ? '取消准备' : '准备';
    } else {
        readyBtn.style.display = 'none';
    }
    document.getElementById('startBtn').style.display = 'inline-block';
    document.getElementById('leaveBtn').style.display = 'inline-block';
    const dissolveBtn = document.getElementById('dissolveBtn');
    if (myPlayer && myPlayer.id === state.hostId) {
        dissolveBtn.style.display = 'inline-block';
    } else {
        dissolveBtn.style.display = 'none';
    }
}

function createPlayerCard(p, state) {
    const card = document.createElement('div');
    card.className = `player-card ${p.spectator ? 'spectator' : ''} ${p.id === playerId ? 'me' : ''}`;
    let readyBadge = '';
    if (!p.spectator && p.ready) {
        readyBadge = '<span class="ready-badge">✓</span>';
    }
    card.innerHTML = `<div class="nickname">${readyBadge} ${p.nickname}</div><div class="role">${p.roleType === 'SPYMASTER' ? '描述者' : p.roleType === 'OPERATIVE' ? '指认者' : '观战'}</div>`;
    if (state.state === 'GROUPING' && !p.spectator) {
        if (p.id === playerId) {
            const swapBtn = document.createElement('button');
            swapBtn.textContent = '交换';
            swapBtn.onclick = (e) => {
                e.stopPropagation();
                swapMode = !swapMode;
                renderGrouping(state);
            };
            card.appendChild(swapBtn);
        } else if (swapMode) {
            const selectBtn = document.createElement('button');
            selectBtn.textContent = '选择交换';
            selectBtn.onclick = (e) => {
                e.stopPropagation();
                send({ type: 'swap_request', targetPlayerId: p.id });
                swapMode = false;
                renderGrouping(state);
            };
            card.appendChild(selectBtn);
        }
    }
    return card;
}

function renderGame(state) {
    const rows = state.rows;
    const cols = state.cols;
    boardEl.style.gridTemplateColumns = `repeat(${cols}, 1fr)`;
    boardEl.innerHTML = '';
    state.board.forEach((card, index) => {
        const cell = document.createElement('div');
        cell.className = 'card';
        if (card.revealed) {
            cell.classList.add('revealed', card.color.toLowerCase());
        } else if (myPlayer && myPlayer.roleType === 'SPYMASTER' && !myPlayer.spectator) {
            if (card.color === 'RED') cell.classList.add('spymaster-red');
            else if (card.color === 'BLUE') cell.classList.add('spymaster-blue');
            else if (card.color === 'GREEN') cell.classList.add('spymaster-green');
            else if (card.color === 'PURPLE') cell.classList.add('spymaster-purple');
            else if (card.color === 'CIVILIAN') cell.classList.add('spymaster-civilian');
            else if (card.color === 'BLACK') cell.classList.add('spymaster-black');
        }
        if (state.highlightedIndex === index) cell.classList.add('highlighted');
        cell.textContent = card.word;
        cell.onclick = () => {
            if (myPlayer && myPlayer.roleType === 'OPERATIVE' && myPlayer.team === state.currentTurnTeam && !card.revealed) {
                send({ type: 'select_card', index });
            }
        };
        boardEl.appendChild(cell);
    });
    let infoHtml = '';
    const teamNames = ['RED', 'BLUE', 'GREEN', 'PURPLE'];
    for (let i = 0; i < state.teamCount; i++) {
        const t = teamNames[i];
        infoHtml += `<span>${t}: ${state[`${t.toLowerCase()}Left`]} </span>`;
    }
    infoHtml += `<span>平民: ${state.civilianLeft}</span>`;
    document.getElementById('gameInfo').innerHTML = infoHtml;

    const isMyTurn = myPlayer && myPlayer.team === state.currentTurnTeam;
    document.getElementById('revealBtn').style.display = (isMyTurn && myPlayer.roleType === 'SPYMASTER') ? 'inline-block' : 'none';
    const endTurnBtn = document.getElementById('endTurnBtn');
    if (state.teamCount === 1 && myPlayer && myPlayer.team === state.currentTurnTeam && myPlayer.roleType === 'OPERATIVE') {
        endTurnBtn.disabled = true;
        endTurnBtn.style.display = 'inline-block';
    } else {
        endTurnBtn.disabled = false;
        endTurnBtn.style.display = (isMyTurn && myPlayer.roleType === 'OPERATIVE') ? 'inline-block' : 'none';
    }
    const isHost = myPlayer && myPlayer.id === state.hostId;
    document.getElementById('regroupBtn').style.display = isHost ? 'inline-block' : 'none';
    document.getElementById('restartBtn').style.display = isHost ? 'inline-block' : 'none';
}

function renderGameOver(state) {
    document.getElementById('winnerText').textContent = state.winner === 'DRAW' ? '平局！' : `${state.winner}获胜！`;
    const board = document.getElementById('endBoard');
    board.style.gridTemplateColumns = `repeat(${state.cols}, 1fr)`;
    board.innerHTML = '';
    state.board.forEach((card, index) => {
        const cell = document.createElement('div');
        cell.className = 'card';
        cell.textContent = card.word;
        cell.dataset.index = index;
        cell.addEventListener('click', () => {
            cell.classList.toggle('selected');
        });
        board.appendChild(cell);
    });
    document.getElementById('leaveEndBtn').style.display = 'inline-block';
    const isHost = myPlayer && myPlayer.id === state.hostId;
    document.getElementById('regroupEndBtn').style.display = isHost ? 'inline-block' : 'none';
    document.getElementById('restartEndBtn').style.display = isHost ? 'inline-block' : 'none';
    if (myPlayer && !myPlayer.spectator) {
        document.getElementById('readyEndBtn').style.display = 'inline-block';
        const ready = myPlayer.ready;
        document.getElementById('readyEndBtn').textContent = ready ? '取消准备' : '准备';
    } else {
        document.getElementById('readyEndBtn').style.display = 'none';
    }
    document.getElementById('discardBtn').style.display = 'inline-block';
}

function showSwapRequest(msg) {
    pendingSwapRequest = msg;
    document.getElementById('swapText').textContent = `${msg.fromNickname} 请求与你交换座位`;
    document.getElementById('swapModal').style.display = 'block';
}

function showPendingAction(msg) {
    document.getElementById('pendingText').textContent = `${msg.fromNickname} 请求${msg.action === 'REGROUP' ? '重新分组' : '重开一局'}，是否同意？`;
    document.getElementById('pendingModal').style.display = 'block';
}

function showError(message) {
    errorToast.textContent = message;
    errorToast.style.display = 'block';
    clearTimeout(errorTimeout);
    errorTimeout = setTimeout(() => { errorToast.style.display = 'none'; }, 3000);
}

function formatTime(seconds) {
    const s = Math.max(0, Math.floor(seconds));
    const mm = String(Math.floor(s / 60)).padStart(2, '0');
    const ss = String(s % 60).padStart(2, '0');
    return `${mm}:${ss}`;
}

// 按钮事件
document.getElementById('readyBtn').addEventListener('click', () => {
    if (!myPlayer || myPlayer.spectator) return;
    // 乐观更新本地玩家准备状态
    myPlayer.ready = !myPlayer.ready;
    renderGrouping(currentRoom);
    send({ type: 'toggle_ready' });
});
document.getElementById('readyEndBtn').addEventListener('click', () => {
    if (!myPlayer || myPlayer.spectator) return;
    myPlayer.ready = !myPlayer.ready;
    renderGameOver(currentRoom);
    send({ type: 'toggle_ready' });
});
document.getElementById('startBtn').addEventListener('click', () => {
    send({ type: 'start_game' });
});
document.getElementById('leaveBtn').addEventListener('click', () => {
    if (confirm('确定要离开房间吗？')) send({ type: 'leave_room' });
});
document.getElementById('dissolveBtn').addEventListener('click', () => {
    if (confirm('确定要解散房间吗？此操作不可撤销！')) {
        setTimeout(() => {
            send({ type: 'dissolve_room' });
        }, 3000);
    }
});
document.getElementById('swapAccept').addEventListener('click', () => {
    if (pendingSwapRequest) {
        send({ type: 'swap_accept', fromPlayerId: pendingSwapRequest.fromPlayerId });
    }
    closeSwapModal();
});
document.getElementById('swapReject').addEventListener('click', closeSwapModal);
document.getElementById('pendingAgree').addEventListener('click', () => {
    send({ type: 'pending_action_agree' });
    document.getElementById('pendingModal').style.display = 'none';
});
document.getElementById('pendingReject').addEventListener('click', () => {
    document.getElementById('pendingModal').style.display = 'none';
});
document.getElementById('revealBtn').addEventListener('click', () => send({ type: 'reveal_result' }));
document.getElementById('endTurnBtn').addEventListener('click', () => send({ type: 'end_turn' }));
document.getElementById('regroupBtn').addEventListener('click', () => {
    if (confirm('确定要发起重新分组吗？')) send({ type: 'regroup_request' });
});
document.getElementById('restartBtn').addEventListener('click', () => {
    if (confirm('确定要发起重开一局吗？')) send({ type: 'restart_request' });
});
document.getElementById('regroupEndBtn').addEventListener('click', () => {
    if (confirm('确定要发起重新分组吗？')) send({ type: 'regroup_request' });
});
document.getElementById('restartEndBtn').addEventListener('click', () => {
    if (confirm('确定要发起重开一局吗？')) send({ type: 'restart_request' });
});
document.getElementById('leaveEndBtn').addEventListener('click', () => {
    if (confirm('确定要离开房间吗？')) send({ type: 'leave_room' });
});
document.getElementById('discardBtn').addEventListener('click', () => {
    const selected = [];
    document.querySelectorAll('#endBoard .card.selected').forEach(c => {
        selected.push(c.textContent);
    });
    if (selected.length === 0) { alert('请先选择要弃用的词汇'); return; }
    if (confirm(`确定弃用 ${selected.length} 个词汇吗？`)) {
        send({ type: 'discard_words', words: selected });
    }
});

function closeSwapModal() {
    document.getElementById('swapModal').style.display = 'none';
    pendingSwapRequest = null;
}

setInterval(() => {
    if (ws && ws.readyState === WebSocket.OPEN) send({ type: 'ping' });
}, 1000);