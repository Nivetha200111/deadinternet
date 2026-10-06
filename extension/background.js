// The toolbar button opens or closes the overlay on the current X tab.
chrome.action.onClicked.addListener(tab => {
  if (tab.id != null) chrome.tabs.sendMessage(tab.id, { type: 'lens:toggle' }).catch(() => {});
});
