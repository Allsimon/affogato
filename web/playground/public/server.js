importScripts("affogato.js");
onmessage = e => {
  for (const message of JSON.parse(handle(JSON.stringify(e.data)))) postMessage(message);
};
