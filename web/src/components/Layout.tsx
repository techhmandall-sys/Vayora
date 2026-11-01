import { useQuery } from '@tanstack/react-query';
import {
  Activity,
  Award,
  BadgeCheck,
  BadgePercent,
  Bell,
  Bus,
  Car,
  CarFront,
  CirclePlus,
  Ellipsis,
  FlaskConical,
  Flag,
  Fuel,
  IndianRupee,
  Languages,
  LayoutDashboard,
  LogOut,
  Monitor,
  Moon,
  Route,
  ScrollText,
  Search,
  Siren,
  Sun,
  Ticket,
  TrendingUp,
  UserRound,
  Users,
  Wallet,
  type LucideIcon,
} from 'lucide-react';
import { Suspense, useEffect, useState } from 'react';
import { Navigate, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';

import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { LANGUAGES, msg, useLanguage, useT, type Language } from '../lib/i18n';
import { PHONE, useMediaQuery } from '../lib/media';
import { themeLabels, useTheme, type ThemeChoice } from '../lib/theme';
import { preload } from '../pageLoaders';
import type { Role } from '../lib/types';
import { ErrorBoundary } from './ErrorBoundary';
import { CommandPalette, type Command } from './palette';
import { initialsOf } from './photo';
import { Sheet } from './sheet';
import { SosPanel } from './Sos';
import { Button, PageSkeleton } from './ui';

interface Section {
  to: string;
  label: string;
  icon: LucideIcon;
}

const links: Record<Role, Section[]> = {
  PASSENGER: [
    { to: '/search', label: msg('Find a ride'), icon: Search },
    { to: '/bookings', label: msg('My bookings'), icon: Ticket },
    { to: '/payments', label: msg('Payments'), icon: Wallet },
    { to: '/passes', label: msg('Passes'), icon: BadgePercent },
  ],
  DRIVER: [
    { to: '/rides', label: msg('My rides'), icon: Car },
    { to: '/rides/new', label: msg('Publish a ride'), icon: CirclePlus },
    { to: '/vehicles', label: msg('My vehicles'), icon: CarFront },
    { to: '/earnings', label: msg('Earnings'), icon: IndianRupee },
    { to: '/record', label: msg('My record'), icon: Award },
  ],
  ADMIN: [
    { to: '/admin', label: msg('Overview'), icon: LayoutDashboard },
    { to: '/admin/reports', label: msg('Reports'), icon: Flag },
    { to: '/admin/emergencies', label: msg('Emergencies'), icon: Siren },
    { to: '/admin/document-checks', label: msg('Document checks'), icon: BadgeCheck },
    { to: '/admin/users', label: msg('Members'), icon: Users },
    { to: '/admin/rides', label: msg('Rides'), icon: Route },
    { to: '/admin/bookings', label: msg('Bookings'), icon: Ticket },
    { to: '/admin/buses', label: msg('Bus services'), icon: Bus },
    { to: '/admin/stopovers', label: msg('Rest stops and chargers'), icon: Fuel },
    { to: '/admin/supply-gaps', label: msg('Unmet demand'), icon: TrendingUp },
    { to: '/admin/durability', label: msg('Booking durability'), icon: Activity },
    { to: '/admin/what-if', label: msg('What if'), icon: FlaskConical },
    { to: '/admin/audit', label: msg('Audit log'), icon: ScrollText },
  ],
};

const account: Section = { to: '/account', label: msg('My account'), icon: UserRound };

export const homeFor = (role: Role): string => links[role][0]?.to ?? '/';

// Sections as iOS-style pills: the current one tinted, the rest quiet until hovered.
const navLink = ({ isActive }: { isActive: boolean }) =>
  `inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full px-3.5 py-1.5 text-sm font-medium ` +
  `focus:outline-none focus-visible:ring-4 focus-visible:ring-teal-600/30 ${
    isActive ? 'bg-teal-600/[0.12] font-semibold text-teal-800' : 'text-slate-600 hover:bg-slate-900/[0.05] hover:text-slate-900'
  }`;

// A tab in the phone's bottom bar: icon over a short label, tinted when current.
const tabLink = ({ isActive }: { isActive: boolean }) =>
  `relative flex min-w-0 flex-1 flex-col items-center gap-0.5 rounded-2xl px-1 py-1.5 text-[11px] font-medium ` +
  `focus:outline-none focus-visible:ring-4 focus-visible:ring-teal-600/30 ${isActive ? 'text-teal-700' : 'text-slate-500'}`;

/** The VAYORA mark: a route between two towns. */
function Logo() {
  return (
    <svg viewBox="0 0 32 32" aria-hidden="true" className="h-8 w-8 flex-none drop-shadow-sm">
      <defs>
        <linearGradient id="vayora-mark" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#14b8a6" />
          <stop offset="1" stopColor="#0f766e" />
        </linearGradient>
      </defs>
      <rect width="32" height="32" rx="9" fill="url(#vayora-mark)" />
      <path d="M9 22c3-9 11-3 14-12" fill="none" stroke="#fff" strokeWidth="2.6" strokeLinecap="round" />
      <circle cx="9" cy="22" r="2.6" fill="#fff" />
      <circle cx="23" cy="10" r="2.6" fill="#fff" />
    </svg>
  );
}

function useUnread() {
  // Checked once a minute while the tab is visible; react-query pauses it in the background.
  const unread = useQuery({
    queryKey: ['notifications', 'unread-count'],
    queryFn: () => api<{ unread: number }>('/notifications/unread-count'),
    refetchInterval: 60_000,
  });
  return unread.data?.unread ?? 0;
}

function UnreadBadge({ count, floating = false }: { count: number; floating?: boolean }) {
  const t = useT();
  if (count <= 0) return null;
  return (
    <span
      key={count}
      className={`inline-flex min-w-[1.25rem] animate-badge-pop items-center justify-center rounded-full bg-danger px-1.5 text-[11px] font-bold leading-5 text-white ${
        floating ? 'absolute -top-0.5 left-1/2 ml-1.5 ring-2 ring-surface' : 'ml-0.5'
      }`}
    >
      {count}
      <span className="sr-only"> {t('unread')}</span>
    </span>
  );
}

function NotificationsLink() {
  const t = useT();
  const count = useUnread();
  return (
    <NavLink to="/notifications" className={navLink} onMouseEnter={() => preload('/notifications')} onFocus={() => preload('/notifications')}>
      <Bell aria-hidden="true" className="h-4 w-4" />
      {t('Notifications')}
      <UnreadBadge count={count} />
    </NavLink>
  );
}

/** Each language is listed in its own script, so it can be found by someone who reads only that one. */
function LanguageSwitcher() {
  const t = useT();
  const { language, setLanguage } = useLanguage();
  return (
    <label className="flex items-center text-sm text-slate-600">
      <span className="sr-only">{t('Language')}</span>
      <select
        value={language}
        onChange={(event) => setLanguage(event.target.value as Language)}
        className="select-ios rounded-full border-0 bg-slate-900/[0.05] py-1.5 pl-3 text-sm font-medium text-slate-800 hover:bg-slate-900/[0.09] focus:outline-none focus-visible:ring-4 focus-visible:ring-teal-600/30"
      >
        {LANGUAGES.map((option) => (
          <option key={option.code} value={option.code} lang={option.code}>
            {option.name}
          </option>
        ))}
      </select>
    </label>
  );
}

const themeOrder: ThemeChoice[] = ['system', 'light', 'dark'];
const themeIcons: Record<ThemeChoice, LucideIcon> = { system: Monitor, light: Sun, dark: Moon };

/** One tap moves through match device, light and dark; the name says which is on. */
function ThemeSwitcher() {
  const t = useT();
  const { choice, setChoice } = useTheme();
  const Icon = themeIcons[choice];
  const nextChoice = themeOrder[(themeOrder.indexOf(choice) + 1) % themeOrder.length];
  return (
    <button
      type="button"
      onClick={() => setChoice(nextChoice)}
      aria-label={t('Theme: {name}', { name: t(themeLabels[choice]) })}
      title={t('Theme: {name}', { name: t(themeLabels[choice]) })}
      className="inline-flex h-8 w-8 items-center justify-center rounded-full bg-slate-900/[0.05] text-slate-700 hover:bg-slate-900/[0.09] active:scale-90 focus:outline-none focus-visible:ring-4 focus-visible:ring-teal-600/30"
    >
      <Icon key={choice} aria-hidden="true" className="h-4 w-4 animate-badge-pop" />
    </button>
  );
}

/** What the command palette offers a member: every page of their role, then quick settings and actions. */
function useCommands(role: Role, actions: { emergency?: () => void; signOut: () => void }): Command[] {
  const navigate = useNavigate();
  const { setChoice } = useTheme();
  const { setLanguage } = useLanguage();
  const pages = [...links[role], { to: '/notifications', label: msg('Notifications'), icon: Bell }, account];
  return [
    ...pages.map(({ to, label, icon }) => ({
      id: `page:${to}`, group: msg('Pages'), label, icon, run: () => navigate(to), onHighlight: () => preload(to),
    })),
    ...themeOrder.map((choice) => ({
      id: `theme:${choice}`, group: msg('Theme'), label: themeLabels[choice], icon: themeIcons[choice], run: () => setChoice(choice),
    })),
    ...LANGUAGES.map(({ code, name }) => ({
      id: `language:${code}`, group: msg('Language'), label: name, literal: true, icon: Languages, run: () => setLanguage(code),
    })),
    ...(actions.emergency ? [{ id: 'emergency', group: msg('Actions'), label: msg('Emergency'), icon: Siren, run: actions.emergency }] : []),
    { id: 'sign-out', group: msg('Actions'), label: msg('Sign out'), icon: LogOut, run: actions.signOut },
  ];
}

function MemberPalette({ role, emergency, signOut }: { role: Role; emergency?: () => void; signOut: () => void }) {
  return <CommandPalette commands={useCommands(role, { emergency, signOut })} />;
}

/** iOS-style navigation for phones: the main sections along the bottom, the rest in a sheet. */
function PhoneTabBar({ role, onSignOut }: { role: Role; onSignOut: () => void }) {
  const t = useT();
  const count = useUnread();
  const location = useLocation();
  const [more, setMore] = useState(false);
  const main = links[role].slice(0, 3);
  const rest = [...links[role].slice(3), account];
  const inRest = rest.some((section) => location.pathname === section.to);
  return (
    <>
      <nav
        aria-label={t('Main')}
        className="glass fixed inset-x-0 bottom-0 z-40 flex items-stretch gap-1 border-t border-slate-900/[0.07] px-2 pb-[max(0.4rem,env(safe-area-inset-bottom))] pt-1.5 print:hidden"
      >
        {main.map(({ to, label, icon: Icon }) => (
          <NavLink key={to} to={to} end className={tabLink} onTouchStart={() => preload(to)} onFocus={() => preload(to)}>
            <Icon aria-hidden="true" className="h-6 w-6" strokeWidth={2} />
            <span className="max-w-full truncate">{t(label)}</span>
          </NavLink>
        ))}
        <NavLink to="/notifications" className={tabLink}>
          <Bell aria-hidden="true" className="h-6 w-6" strokeWidth={2} />
          <span className="max-w-full truncate">{t('Notifications')}</span>
          <UnreadBadge count={count} floating />
        </NavLink>
        <button type="button" onClick={() => setMore(true)} aria-expanded={more} className={tabLink({ isActive: inRest })}>
          <Ellipsis aria-hidden="true" className="h-6 w-6" strokeWidth={2} />
          <span>{t('More')}</span>
        </button>
      </nav>
      <Sheet open={more} onClose={() => setMore(false)} title={t('More')}>
        <ul className="divide-y divide-slate-900/[0.06] overflow-hidden rounded-2xl bg-slate-50 ring-1 ring-slate-900/[0.05]">
          {rest.map(({ to, label, icon: Icon }) => (
            <li key={to}>
              <NavLink to={to} end onClick={() => setMore(false)} className="flex items-center gap-3 px-4 py-3.5 text-[15px] font-medium text-slate-900 active:bg-slate-100">
                <span aria-hidden="true" className="flex h-8 w-8 items-center justify-center rounded-xl bg-teal-600/[0.12] text-teal-800">
                  <Icon className="h-[18px] w-[18px]" />
                </span>
                {t(label)}
              </NavLink>
            </li>
          ))}
        </ul>
        <Button tone="secondary" className="mt-4 w-full py-3" onClick={onSignOut}>
          <LogOut aria-hidden="true" className="h-4 w-4" />
          {t('Sign out')}
        </Button>
      </Sheet>
    </>
  );
}

export function Layout() {
  const t = useT();
  const { user, logout } = useAuth();
  const [emergency, setEmergency] = useState(false);
  const location = useLocation();
  const phone = useMediaQuery(PHONE);
  const member = user && user.role !== 'ADMIN';

  // Each role has its own tint (index.css): the whole app takes it on.
  useEffect(() => {
    const root = document.documentElement;
    if (user) root.dataset.role = user.role;
    else delete root.dataset.role;
  }, [user]);

  return (
    <div className="min-h-screen bg-canvas text-slate-900">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:z-50 focus:rounded-full focus:bg-surface focus:px-4 focus:py-2 focus:shadow-float"
      >
        {t('Skip to content')}
      </a>
      <header className="glass sticky top-0 z-40 border-b border-slate-900/[0.07] print:hidden">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-x-4 gap-y-2 px-4 py-2.5 sm:px-6">
          <span className="flex items-center gap-2.5 text-[17px] font-bold tracking-tight text-slate-900" translate="no">
            <Logo />
            {'VAYORA'}
          </span>
          <div className="ml-auto flex flex-wrap items-center gap-2 text-sm text-slate-600">
            {user && <MemberPalette role={user.role} emergency={member ? () => setEmergency(true) : undefined} signOut={logout} />}
            <LanguageSwitcher />
            <ThemeSwitcher />
            {!user && location.pathname !== '/login' && location.pathname !== '/register' && (
              <NavLink to="/login" className="rounded-full bg-accent px-4 py-1.5 text-sm font-semibold text-white shadow-md shadow-accent/25 hover:bg-accent-strong focus:outline-none focus-visible:ring-4 focus-visible:ring-teal-600/30">
                {t('Sign in')}
              </NavLink>
            )}
            {member && (
              <Button tone="danger" aria-expanded={emergency} onClick={() => setEmergency(true)}>
                <Siren aria-hidden="true" className="h-4 w-4" />
                {t('Emergency')}
              </Button>
            )}
            {user && (
              <>
                <span className="flex items-center gap-2 rounded-full py-1 pl-1 pr-1 sm:pr-3">
                  <span
                    aria-hidden="true"
                    className="inline-flex h-8 w-8 items-center justify-center rounded-full bg-gradient-to-br from-[#14b8a6] to-[#0f766e] text-xs font-bold text-white shadow-sm"
                  >
                    {initialsOf(user.fullName)}
                  </span>
                  <span className="hidden font-medium text-slate-800 sm:inline">{user.fullName}</span>
                </span>
                {!phone && (
                  <Button tone="secondary" onClick={logout}>
                    <LogOut aria-hidden="true" className="h-4 w-4" />
                    {t('Sign out')}
                  </Button>
                )}
              </>
            )}
          </div>
          {user && !phone && (
            <nav
              aria-label={t('Main')}
              className="no-scrollbar -mx-1 flex w-[calc(100%+0.5rem)] items-center gap-1 overflow-x-auto px-1 pb-1"
            >
              {links[user.role].map(({ to, label, icon: Icon }) => (
                <NavLink key={to} to={to} end className={navLink} onMouseEnter={() => preload(to)} onFocus={() => preload(to)}>
                  <Icon aria-hidden="true" className="h-4 w-4" />
                  {t(label)}
                </NavLink>
              ))}
              <NotificationsLink />
              <NavLink to={account.to} className={navLink} onMouseEnter={() => preload(account.to)} onFocus={() => preload(account.to)}>
                <UserRound aria-hidden="true" className="h-4 w-4" />
                {t(account.label)}
              </NavLink>
            </nav>
          )}
        </div>
      </header>
      {member && emergency && <SosPanel onClose={() => setEmergency(false)} />}
      {/* Keyed by the path, so each screen arrives with the page transition and a failure on one clears on the next. */}
      <main
        id="main"
        key={location.pathname}
        className={`mx-auto max-w-5xl animate-page-in px-4 pt-6 sm:px-6 sm:pt-10 print:p-0 ${user && phone ? 'pb-28' : 'pb-20'}`}
      >
        <ErrorBoundary>
          <Suspense fallback={<PageSkeleton />}>
            <Outlet />
          </Suspense>
        </ErrorBoundary>
      </main>
      {user && phone && <PhoneTabBar role={user.role} onSignOut={logout} />}
    </div>
  );
}

/** Lets a route render only for a signed-in user, and with `role` only for that role. */
export function RequireRole({ role }: { role?: Role }) {
  const { user } = useAuth();
  const location = useLocation();
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (role && user.role !== role) return <Navigate to={homeFor(user.role)} replace />;
  return <Outlet />;
}
