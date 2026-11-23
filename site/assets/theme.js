try {
  const preference = localStorage.getItem('klob-wallpaper-theme');
  if (preference === 'dark' || preference === 'light') {
    document.documentElement.dataset.theme = preference;
  }
} catch {}
