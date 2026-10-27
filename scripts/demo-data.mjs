// Fills a local VAYORA with demo data, through the public API as any client would: four
// drivers with vehicles, the coming six days of rides on the Kolhapur to Pune corridor,
// and two passengers with two seats booked each. Every demo account says in its profile
// that it is made up, and uses an address under demo.journex.test, a reserved name that
// mail cannot reach.
//
// Platform events have no run id, so nothing here reaches the research figures, which are
// computed per simulation run (experiments/analysis/metrics.sql).
//
//   DEMO_PASSWORD=<at least 8 characters> node scripts/demo-data.mjs [--api http://localhost:8080]
//
// Safe to run again: accounts are signed into rather than made twice, and a driver who
// already has rides to come gets no more. Refuses any API but this machine's unless
// --allow-remote is given.

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const at = args.indexOf(name);
  return at >= 0 && args[at + 1] ? args[at + 1] : fallback;
};
const api = option('--api', 'http://localhost:8080').replace(/\/$/, '');
const password = process.env.DEMO_PASSWORD ?? '';

if (password.length < 8 || password.length > 72) {
  fail('Set DEMO_PASSWORD to 8 to 72 characters: every demo account signs in with it.');
}
if (!['localhost', '127.0.0.1', '[::1]'].includes(new URL(api).hostname) && !args.includes('--allow-remote')) {
  fail(`${api} is not this machine. Demo accounts share one password; add --allow-remote only for a server nobody relies on.`);
}

const DOMAIN = 'demo.journex.test';
const BIO = 'Demo account, made up by scripts/demo-data.mjs. Not a real person.';

// The corridor as the publish form offers it (web/src/lib/corridor.ts), in travel order.
const CORRIDOR = [
  { name: 'Kolhapur', lat: 16.705, lon: 74.2433, offsetMinutes: 0 },
  { name: 'Karad', lat: 17.289, lon: 74.1818, offsetMinutes: 75 },
  { name: 'Satara', lat: 17.6805, lon: 74.0183, offsetMinutes: 130 },
  { name: 'Pune', lat: 18.5204, lon: 73.8567, offsetMinutes: 270 },
];
// Rupees per leg, Kolhapur first: about the suggested 2 a kilometre.
const LEG_PRICES = [150, 110, 230];

const DRIVERS = [
  {
    key: 'rohan', fullName: 'Rohan Jadhav',
    profile: { birthYear: 1990, gender: 'MALE', smoking: false, pets: false, music: true, chattiness: 'MODERATE' },
    vehicle: { make: 'Maruti Suzuki', model: 'Ertiga', year: 2021, colour: 'White', seats: 6, comfort: 'COMFORT', luggage: 'MEDIUM' },
    rides: [{ from: 'Kolhapur', at: '06:00' }, { from: 'Pune', at: '17:30' }],
    instantBooking: true, maxDetourMinutes: 10,
  },
  {
    key: 'sneha', fullName: 'Sneha Kulkarni',
    profile: { birthYear: 1994, gender: 'FEMALE', smoking: false, pets: true, music: false, chattiness: 'QUIET' },
    vehicle: { make: 'Hyundai', model: 'Creta', year: 2022, colour: 'Grey', seats: 5, comfort: 'PREMIUM', luggage: 'LARGE' },
    rides: [{ from: 'Pune', at: '07:00' }, { from: 'Kolhapur', at: '15:00' }],
    instantBooking: true, maxDetourMinutes: 0,
  },
  {
    key: 'amit', fullName: 'Amit Patil',
    profile: { birthYear: 1987, gender: 'MALE', smoking: false, pets: false, music: true, chattiness: 'TALKATIVE' },
    vehicle: { make: 'Tata', model: 'Nexon EV', year: 2023, colour: 'Blue', seats: 5, comfort: 'COMFORT', luggage: 'SMALL',
      electric: true, batteryKwh: 40.5, consumptionKwhPer100Km: 14.5 },
    rides: [{ from: 'Kolhapur', at: '09:30' }],
    // This one asks the driver, so the approval flow has something to show.
    instantBooking: false, maxDetourMinutes: 15,
  },
  {
    key: 'priya', fullName: 'Priya Deshmukh',
    profile: { birthYear: 1992, gender: 'FEMALE', smoking: false, pets: false, music: true, chattiness: 'MODERATE' },
    vehicle: { make: 'Honda', model: 'City', year: 2020, colour: 'Red', seats: 5, comfort: 'COMFORT', luggage: 'MEDIUM' },
    rides: [{ from: 'Pune', at: '10:00' }],
    instantBooking: true, maxDetourMinutes: 5,
  },
];

const PASSENGERS = [
  { key: 'asha', fullName: 'Asha More',
    profile: { birthYear: 1998, gender: 'FEMALE', smoking: false, pets: false, music: true, chattiness: 'QUIET' } },
  { key: 'kiran', fullName: 'Kiran Shinde',
    profile: { birthYear: 1996, gender: 'MALE', smoking: false, pets: false, music: false, chattiness: 'MODERATE' } },
];

const DAYS = 6;

async function main() {
  const health = await fetch(`${api}/actuator/health`).catch(() => null);
  if (!health?.ok) {
    fail(`core-api is not answering at ${api}. Start the stack first: docker compose up -d`);
  }

  let published = 0;
  const allRides = [];
  for (const driver of DRIVERS) {
    const session = await account(driver, 'DRIVER');
    const vehicleId = await vehicle(session, driver.vehicle);
    const upcoming = (await call('GET', '/api/v1/rides/mine', null, session.token))
      .filter((ride) => new Date(ride.departureTime) > new Date());
    if (upcoming.length > 0) {
      console.log(`  ${driver.fullName} already has ${upcoming.length} rides to come; none added.`);
      allRides.push(...upcoming);
      continue;
    }
    for (let day = 1; day <= DAYS; day++) {
      for (const plan of driver.rides) {
        const ride = await call('POST', '/api/v1/rides', rideRequest(driver, vehicleId, plan, day), session.token);
        allRides.push(ride);
        published++;
      }
    }
    console.log(`  ${driver.fullName}: ${driver.rides.length * DAYS} rides over the next ${DAYS} days.`);
  }

  const sessions = [];
  for (const passenger of PASSENGERS) {
    sessions.push(await account(passenger, 'PASSENGER'));
  }
  const booked = await bookings(sessions, allRides);

  console.log(`\nDone: ${published} rides published, ${booked} seats booked.`);
  console.log(`Sign in at the web app with any of these and DEMO_PASSWORD:`);
  for (const person of [...PASSENGERS, ...DRIVERS]) {
    console.log(`  ${email(person)}  (${PASSENGERS.includes(person) ? 'passenger' : 'driver'})`);
  }
}

/** Signs the person in, creating the account the first time, and sets its profile. */
async function account(person, role) {
  const created = await call('POST', '/api/v1/auth/register',
    { email: email(person), password, fullName: person.fullName, role }, null, [409]);
  const session = created ?? await call('POST', '/api/v1/auth/login', { email: email(person), password });
  // Every time: a profile replaced with the same values is unchanged, and one an earlier
  // run did not reach is set.
  await call('PUT', '/api/v1/me/profile', { phone: null, bio: BIO, ...person.profile }, session.accessToken);
  if (created) {
    console.log(`  Created ${role.toLowerCase()} ${person.fullName}.`);
  }
  return { token: session.accessToken, id: session.user.id };
}

async function vehicle(session, details) {
  const mine = await call('GET', '/api/v1/vehicles', null, session.token);
  if (mine.length > 0) {
    return mine[0].id;
  }
  return (await call('POST', '/api/v1/vehicles', { electric: false, ...details }, session.token)).id;
}

function rideRequest(driver, vehicleId, plan, day) {
  const outbound = plan.from === 'Kolhapur';
  const stops = outbound ? CORRIDOR
    : [...CORRIDOR].reverse().map((stop) => ({ ...stop, offsetMinutes: 270 - stop.offsetMinutes }));
  const prices = outbound ? LEG_PRICES : [...LEG_PRICES].reverse();
  return {
    vehicleId,
    departureTime: indiaTime(day, plan.at),
    seatsOffered: Math.min(driver.vehicle.seats - 1, 4),
    instantBooking: driver.instantBooking,
    stops: stops.map(({ name, lat, lon, offsetMinutes }) => ({ name, lat, lon, offsetMinutes })),
    segmentPrices: prices,
    notes: 'Demo ride, made up by scripts/demo-data.mjs.',
    maxTwoInBack: true,
    repeatWeeks: 0,
    womenOnly: false,
    maxDetourMinutes: driver.maxDetourMinutes,
  };
}

/**
 * A few seats taken, so lists are not empty: each passenger books the whole way on two
 * rides that confirm at once. Skipped, and said, if payment needs a checkout here.
 */
async function bookings(sessions, rides) {
  const instant = rides.filter((ride) => ride.instantBooking).sort((a, b) => a.departureTime.localeCompare(b.departureTime));
  let booked = 0;
  for (const [index, session] of sessions.entries()) {
    const existing = await call('GET', '/api/v1/bookings', null, session.token).catch(() => []);
    if (existing.length > 0) {
      continue;
    }
    for (const ride of [instant[2 + index * 3], instant[9 + index * 3]].filter(Boolean)) {
      try {
        await call('POST', '/api/v1/bookings', {
          rideId: ride.id, fromSeq: 0, toSeq: ride.stops.length - 1, seats: 1, paymentMethod: 'UPI',
        }, session.token);
        booked++;
      } catch (error) {
        console.log(`  A demo booking was not made: ${error.message}`);
        return booked;
      }
    }
  }
  return booked;
}

/** That time of day in India, so many days from today there, as an instant. */
function indiaTime(day, at) {
  const IST = 330 * 60_000;
  const today = new Date(Date.now() + IST);
  const [hours, minutes] = at.split(':').map(Number);
  return new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate() + day, hours, minutes)
    - IST).toISOString();
}

function email(person) {
  return `${person.key}@${DOMAIN}`;
}

/** The answer's body; null for a status in `allowed`; throws with the server's detail otherwise. */
async function call(method, path, body, token, allowed = []) {
  const response = await fetch(`${api}${path}`, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (allowed.includes(response.status)) {
    return null;
  }
  const text = await response.text();
  if (!response.ok) {
    let detail = text;
    try {
      detail = JSON.parse(text).detail ?? text;
    } catch {
      // Not a problem document; the text is all there is.
    }
    throw new Error(`${method} ${path}: ${response.status} ${typeof detail === 'string' ? detail : JSON.stringify(detail)}`);
  }
  return text ? JSON.parse(text) : null;
}

function fail(message) {
  console.error(message);
  process.exit(1);
}

main().catch((error) => fail(error.message));
