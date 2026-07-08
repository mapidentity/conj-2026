// Dev-only: refresh the page when the server says the source reloaded.
(function () {
  function connect() {
    var ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/dev/ws');
    ws.onmessage = function (e) {
      var m = JSON.parse(e.data);
      if (m.type === 'reload') location.reload();
    };
    ws.onclose = function () { setTimeout(connect, 1000); };
  }
  connect();
})();
