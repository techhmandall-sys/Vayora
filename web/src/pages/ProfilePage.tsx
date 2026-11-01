import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';

import { levelLabels } from '../lib/levels';
import { RatingSummary } from '../components/rating';
import { initialsOf, Photo } from '../components/photo';
import { ReportMember } from '../components/report';
import { Card, Page, QueryState } from '../components/ui';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { formatDate, formatMonthYear, formatPercent } from '../lib/format';
import { msg, useT, useUnits } from '../lib/i18n';
import { idDocumentLabels } from '../lib/idDocuments';
import type { Profile } from '../lib/types';

const chattinessLabels = {
  QUIET: msg('Prefers a quiet ride'),
  MODERATE: msg('Happy with some conversation'),
  TALKATIVE: msg('Likes to talk'),
};

/** What the member said about travelling, as short phrases. Nothing for what they did not say. */
// `!= null` throughout: a field the server left out is not said, never "no".
function preferences(member: Profile): string[] {
  const said: string[] = [];
  if (member.smoking != null) said.push(member.smoking ? msg('Fine with smoking') : msg('No smoking'));
  if (member.pets != null) said.push(member.pets ? msg('Fine with pets') : msg('No pets'));
  if (member.music != null) said.push(member.music ? msg('Likes music on the way') : msg('Prefers no music'));
  if (member.chattiness) said.push(chattinessLabels[member.chattiness]);
  return said;
}

export function ProfilePage() {
  const t = useT();
  const units = useUnits();
  const { id } = useParams();
  const { user } = useAuth();
  const profile = useQuery({ queryKey: ['profile', id], queryFn: () => api<Profile>(`/users/${id}/profile`) });
  const member = profile.data;

  return (
    <Page title={member?.fullName ?? t('Member')}>
      <QueryState isPending={profile.isPending} error={profile.error} isEmpty={false} empty="" onRetry={() => void profile.refetch()} />
      {member && (
        <>
          <Photo path={`/users/${id}/photo`} alt={t('Photo of {name}', { name: member.fullName })} initials={initialsOf(member.fullName)} size="h-24 w-24" />
          <Card>
            <dl className="grid gap-4 text-sm sm:grid-cols-3">
              <div>
                <dt className="text-slate-500">{member.role === 'DRIVER' ? t('Driver since') : t('Passenger since')}</dt>
                <dd className="mt-1 font-medium">{formatMonthYear(member.memberSince)}</dd>
              </div>
              <div>
                <dt className="text-slate-500">{t('Completed trips')}</dt>
                <dd className="mt-1 font-medium">{member.completedTrips}</dd>
              </div>
              <div>
                <dt className="text-slate-500">{t('Rating')}</dt>
                <dd className="mt-1 font-medium">
                  <RatingSummary average={member.averageStars} count={member.ratingCount} />
                </dd>
              </div>
              {member.level && (
                <div>
                  <dt className="text-slate-500">{t('Level')}</dt>
                  <dd className="mt-1 font-medium">{levelLabels[member.level] ? t(levelLabels[member.level]) : member.level}</dd>
                </div>
              )}
              {member.role === 'DRIVER' && (
                <div>
                  <dt className="text-slate-500">{t('Answers requests')}</dt>
                  <dd className="mt-1 font-medium">
                    {member.responseRate == null
                      ? t('Not enough requests yet')
                      : member.responseMinutes == null
                        ? formatPercent(member.responseRate)
                        : t('{rate}, usually within {time}', {
                            rate: formatPercent(member.responseRate),
                            time: units.duration(member.responseMinutes),
                          })}
                  </dd>
                </div>
              )}
            </dl>
            {/* Rendered as text: a bio is whatever someone typed. */}
            {member.bio && <p className="mt-4 whitespace-pre-wrap text-sm text-slate-700">{member.bio}</p>}
            <ul aria-label={t('About this member')} className="mt-4 flex flex-wrap gap-2 text-xs">
              {member.age != null && <li className="rounded-full bg-slate-100 px-2.5 py-0.5">{t('About {n} years old', { n: member.age })}</li>}
              {member.emailVerified && <li className="rounded-full bg-emerald-100 px-2.5 py-0.5 text-emerald-900">{t('Email confirmed')}</li>}
              {member.phoneVerified && <li className="rounded-full bg-emerald-100 px-2.5 py-0.5 text-emerald-900">{t('Phone confirmed')}</li>}
              {member.declaredId &&
                (member.idCheckedAt ? (
                  <li className="rounded-full bg-emerald-100 px-2.5 py-0.5 text-emerald-900">
                    {t('{document}, checked by VAYORA staff', { document: t(idDocumentLabels[member.declaredId]) })}
                  </li>
                ) : (
                  <li className="rounded-full bg-slate-100 px-2.5 py-0.5">
                    {t('Declared: {document}, not checked', { document: t(idDocumentLabels[member.declaredId]) })}
                  </li>
                ))}
              {preferences(member).map((phrase) => (
                <li key={phrase} className="rounded-full bg-slate-100 px-2.5 py-0.5">
                  {t(phrase)}
                </li>
              ))}
            </ul>
            <p className="mt-3 text-xs text-slate-500">
              {member.idCheckedAt && member.declaredId
                ? t('A person at VAYORA saw this document. It was not checked with its issuer.')
                : t('VAYORA has not checked any identity documents.')}
            </p>
          </Card>
          <section aria-label={t('Reviews')} className="space-y-3">
            <h2 className="text-lg font-semibold">{t('Reviews')}</h2>
            {member.reviews.length === 0 && <p className="text-sm text-slate-600">{t('Nobody has written a review yet.')}</p>}
            {member.reviews.map((review, index) => (
              <Card key={index}>
                <p className="text-sm font-medium">
                  {t('{stars} out of 5 · {reviewer} · {date}', {
                    stars: review.stars,
                    reviewer: review.reviewerName,
                    date: formatDate(review.createdAt),
                  })}
                </p>
                {/* Rendered as text: a review is whatever someone typed. */}
                <p className="mt-1 whitespace-pre-wrap text-sm text-slate-700">{review.comment}</p>
              </Card>
            ))}
          </section>
          {user && user.role !== 'ADMIN' && user.id !== member.id && <ReportMember userId={member.id} name={member.fullName} />}
        </>
      )}
    </Page>
  );
}
