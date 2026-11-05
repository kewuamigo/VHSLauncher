// Weather from Open-Meteo (no key); location from the saved city, else from the IP address.

/** WMO weather interpretation codes, as used by Open-Meteo. */
function kindOf(code, day) {
  if (code === 0) return day ? 'SUN' : 'MOON';
  if (code === 1 || code === 2) return 'PARTLY';
  if (code === 3) return 'CLOUD';
  if (code === 45 || code === 48) return 'FOG';
  if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return 'RAIN';
  if ((code >= 71 && code <= 77) || code === 85 || code === 86) return 'SNOW';
  if (code >= 95 && code <= 99) return 'STORM';
  return 'CLOUD';
}

async function get(url) {
  try {
    const r = await fetch(url, { signal: AbortSignal.timeout(8000) });
    return r.ok ? await r.json() : null;
  } catch { return null; }
}

async function locateByIp() {
  const a = await get('https://ipwho.is/');
  if (a?.success) return { name: a.city, lat: a.latitude, lon: a.longitude };
  const b = await get('https://ipapi.co/json/');
  if (b?.latitude != null) return { name: b.city, lat: b.latitude, lon: b.longitude };
  return null;
}

export async function fetchWeather(prefs) {
  const place = prefs.cityName ? { name: prefs.cityName, lat: prefs.cityLat, lon: prefs.cityLon } : await locateByIp();
  if (!place) return null;
  const unit = prefs.fahrenheit ? 'fahrenheit' : 'celsius';
  const j = await get(`https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}` +
    `&current=temperature_2m,weather_code,is_day&temperature_unit=${unit}&timezone=auto`);
  const cur = j?.current;
  if (!cur) return null;
  const t = Math.round(cur.temperature_2m);
  return { text: `${t}°${prefs.fahrenheit ? 'F' : 'C'}`, kind: kindOf(cur.weather_code, cur.is_day === 1), city: place.name };
}

/** Looks a city name up with Open-Meteo's geocoder. */
export async function geocode(name) {
  const j = await get(`https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(name.trim())}&count=1`);
  const r = j?.results?.[0];
  return r ? { name: r.name, lat: r.latitude, lon: r.longitude } : null;
}
