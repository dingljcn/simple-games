// ========== 全局错误捕获 ==========
window.onerror = function(message, source, lineno, colno, error) {
    showError('脚本错误：' + message + ' (行' + lineno + ')');
    return true;
};

// ========== 变量声明（必须在 load_room 调用之前） ==========
let ws;
let playerId = null;
let currentRoom = null;
let myPlayer = null;
let swapMode = false;
let pendingSwapRequest = null;
let errorTimeout;

const roomDisplay = document.getElementById('roomDisplay');
const stateDisplay = document.getElementById('stateDisplay');
const blueTime = document.getElementById('blueTime');
const redTime = document.getElementById('redTime');
const userInfo = document.getElementById('userInfo');
const groupingView = document.getElementById('groupingView');
const gameView = document.getElementById('gameView');
const gameOverView = document.getElementById('gameOverView');
const boardEl = document.getElementById('board');
const redPlayersEl = document.getElementById('redPlayers');
const bluePlayersEl = document.getElementById('bluePlayers');
const errorToast = document.getElementById('errorToast');

function load_room(room) {
    if (!room || room === '0' || !/^\d{4}$/.test(room)) {
        location.href = 'entry.html';
        return;
    }
    const nickname = localStorage.getItem('nickname');
    if (!nickname) {
        location.href = 'entry.html';
        return;
    }
    connect();
}

const room = sessionStorage.getItem('room');
load_room(room);

// ========== WebSocket 逻辑 ==========
function connect() {
    const protocol = location.protocol === 'https:' ? 'wss://' : 'ws://';
    ws = new WebSocket(protocol + location.host + '/ws');
    ws.onopen = () => {
        send({ type: 'join', roomId: room, nickname: localStorage.getItem('nickname') });
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
        case 'error':
            showError(msg.message);
            break;
    }
}

function renderAll(state) {
    roomDisplay.textContent = state.roomId;
    stateDisplay.textContent = state.state;
    blueTime.textContent = formatTime(state.blueElapsedSeconds);
    redTime.textContent = formatTime(state.redElapsedSeconds);

    // 更新当前用户信息
    if (myPlayer) {
        const teamName = myPlayer.team === 'RED' ? '红方' : '蓝方';
        const roleName = myPlayer.roleType === 'SPYMASTER' ? '描述者' : '指认者';
        userInfo.textContent = `昵称：${myPlayer.nickname}，角色：${teamName}${roleName}`;
    } else {
        userInfo.textContent = '';
    }

    // 同步配置输入框
    if (state.state === 'GROUPING') {
        document.getElementById('rowsInput').value = state.rows || 5;
        document.getElementById('colsInput').value = state.cols || 5;
        document.getElementById('blackInput').value = state.blackCount || 1;
        document.getElementById('teamCountInput').value = state.teamCount || 8;
    }

    if (state.state === 'GROUPING') {
        groupingView.style.display = 'block';
        gameView.style.display = 'none';
        gameOverView.style.display = 'none';
        renderGrouping(state);
    } else if (state.state === 'BLUE_TURN' || state.state === 'RED_TURN') {
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
    redPlayersEl.innerHTML = '';
    bluePlayersEl.innerHTML = '';
    const redPlayers = state.players.filter(p => p.team === 'RED');
    const bluePlayers = state.players.filter(p => p.team === 'BLUE');
    redPlayers.forEach(p => redPlayersEl.appendChild(createPlayerCard(p, state)));
    bluePlayers.forEach(p => bluePlayersEl.appendChild(createPlayerCard(p, state)));

    document.querySelectorAll('.firstBtn').forEach(btn => {
        if (btn.dataset.team === state.firstTeam) {
            btn.classList.add('active');
        } else {
            btn.classList.remove('active');
        }
    });
}

function createPlayerCard(p, state) {
    const card = document.createElement('div');
    card.className = `player-card ${p.team.toLowerCase()} ${p.id === playerId ? 'me' : ''}`;
    card.innerHTML = `<div class="nickname">${p.nickname}</div><div class="role">${p.roleType === 'SPYMASTER' ? '描述者' : '指认者'}</div>`;
    if (state.state === 'GROUPING') {
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
    const rows = state.rows || 5;
    const cols = state.cols || 5;
    boardEl.style.gridTemplateColumns = `repeat(${cols}, 1fr)`;
    boardEl.innerHTML = '';
    const board = state.board;
    board.forEach((card, index) => {
        const cell = document.createElement('div');
        cell.className = 'card';

        if (card.revealed) {
            cell.classList.add('revealed');
            if (card.color === 'RED') {
                cell.classList.add('red');
            } else if (card.color === 'BLUE') {
                cell.classList.add('blue');
            } else if (card.color === 'CIVILIAN') {
                cell.classList.add('civilian');
            } else if (card.color === 'BLACK') {
                cell.classList.add('black');
            }
        } else {
            if (myPlayer && myPlayer.roleType === 'SPYMASTER') {
                if (card.color === 'RED') {
                    cell.classList.add('spymaster-red');
                } else if (card.color === 'BLUE') {
                    cell.classList.add('spymaster-blue');
                } else if (card.color === 'CIVILIAN') {
                    cell.classList.add('spymaster-civilian');
                } else if (card.color === 'BLACK') {
                    cell.classList.add('spymaster-black');
                }
            }
        }

        if (state.highlightedIndex === index) {
            cell.classList.add('highlighted');
        }

        cell.textContent = card.word;
        cell.onclick = () => {
            if (myPlayer && myPlayer.roleType === 'OPERATIVE' && myPlayer.team === state.currentTurn && !card.revealed) {
                send({ type: 'select_card', index });
            }
        };
        boardEl.appendChild(cell);
    });

    document.getElementById('redLeft').textContent = state.redLeft;
    document.getElementById('blueLeft').textContent = state.blueLeft;
    document.getElementById('civilianLeft').textContent = state.civilianLeft;

    const revealBtn = document.getElementById('revealBtn');
    const endTurnBtn = document.getElementById('endTurnBtn');
    const isMyTurn = myPlayer && myPlayer.team === state.currentTurn;
    if (isMyTurn && myPlayer && myPlayer.roleType === 'SPYMASTER') {
        revealBtn.style.display = 'inline-block';
    } else {
        revealBtn.style.display = 'none';
    }
    if (isMyTurn && myPlayer && myPlayer.roleType === 'OPERATIVE') {
        endTurnBtn.style.display = 'inline-block';
    } else {
        endTurnBtn.style.display = 'none';
    }
}

function renderGameOver(state) {
    const winnerText = document.getElementById('winnerText');
    if (state.winner === 'DRAW') {
        winnerText.textContent = '平局！';
    } else if (state.winner === 'RED') {
        winnerText.textContent = '红方胜利！';
    } else if (state.winner === 'BLUE') {
        winnerText.textContent = '蓝方胜利！';
    } else {
        winnerText.textContent = '游戏结束';
    }
}

function showSwapRequest(msg) {
    pendingSwapRequest = msg;
    document.getElementById('swapText').textContent = `${msg.fromNickname} 请求与你交换座位`;
    document.getElementById('swapModal').style.display = 'block';
}

function closeSwapModal() {
    document.getElementById('swapModal').style.display = 'none';
    pendingSwapRequest = null;
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

// ========== 按钮事件绑定 ==========
document.querySelectorAll('.firstBtn').forEach(btn => {
    btn.addEventListener('click', () => {
        send({ type: 'set_first_team', team: btn.dataset.team });
    });
});

document.getElementById('startBtn').addEventListener('click', () => {
    const rows = parseInt(document.getElementById('rowsInput').value, 10) || 5;
    const cols = parseInt(document.getElementById('colsInput').value, 10) || 5;
    const blackCount = parseInt(document.getElementById('blackInput').value, 10) || 1;
    const teamCount = parseInt(document.getElementById('teamCountInput').value, 10) || 8;
    send({
        type: 'start_game',
        rows: rows,
        cols: cols,
        blackCount: blackCount,
        teamCount: teamCount
    });
});

document.getElementById('revealBtn').addEventListener('click', () => {
    send({ type: 'reveal_result' });
});

document.getElementById('endTurnBtn').addEventListener('click', () => {
    send({ type: 'end_turn' });
});

document.getElementById('swapAccept').addEventListener('click', () => {
    if (pendingSwapRequest) {
        send({ type: 'swap_accept', fromPlayerId: pendingSwapRequest.fromPlayerId });
    }
    closeSwapModal();
});

document.getElementById('swapReject').addEventListener('click', closeSwapModal);

document.getElementById('adjustBtn').addEventListener('click', () => {
    send({ type: 'adjust_groups' });
});

document.getElementById('playAgainBtn').addEventListener('click', () => {
    send({ type: 'play_again' });
});

// 每秒 ping 一次，用于刷新计时
setInterval(() => {
    if (ws && ws.readyState === WebSocket.OPEN) {
        send({ type: 'ping' });
    }
}, 1000);