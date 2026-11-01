import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { BadgeCheck } from 'lucide-react';

import { api } from '../lib/api';
import { formatDate } from '../lib/format';
import { useT } from '../lib/i18n';
import type { DocumentCheck, DocumentCheckKind } from '../lib/types';
import { Button, Detail, Notice } from './ui';

/**
 * Where something a driver declared stands with VAYORA staff, and the way to ask them to see
 * it (features 7 and 58). The driver shows the original; only the outcome is kept, and it says
 * a person saw the document, not that its issuer stands behind it.
 */
export function DocumentCheckStatus({ kind, vehicleId = null }: { kind: DocumentCheckKind; vehicleId?: number | null }) {
  const t = useT();
  const queryClient = useQueryClient();
  const checks = useQuery({ queryKey: ['me', 'document-checks'], queryFn: () => api<DocumentCheck[]>('/me/document-checks') });
  const ask = useMutation({
    mutationFn: () => api<DocumentCheck>('/me/document-checks', { method: 'POST', body: { kind, vehicleId } }),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['me', 'document-checks'] }),
  });
  if (!checks.data) return null;

  const check = checks.data.find((one) => one.kind === kind && (kind === 'IDENTITY' || one.vehicleId === vehicleId));
  if (check?.status === 'CHECKED' && check.decidedAt) {
    return (
      <Detail icon={BadgeCheck} tone="text-emerald-800">
        {t('Checked by VAYORA staff on {date}. A person saw it; it was not checked with its issuer.', { date: formatDate(check.decidedAt) })}
      </Detail>
    );
  }
  if (check?.status === 'WAITING') {
    return <p className="text-sm text-slate-600">{t('Waiting for VAYORA staff. They will contact you to see the original.')}</p>;
  }
  return (
    <div className="space-y-2 text-sm">
      {check?.status === 'REFUSED' && <Notice tone="info">{t('VAYORA staff could not confirm it: {note}', { note: check.note ?? '' })}</Notice>}
      <p className="text-slate-600">{t('You show the original to VAYORA staff, in person or on a video call. They record only the result: no number and no copy is kept.')}</p>
      {ask.error && <Notice tone="error">{ask.error.message}</Notice>}
      <Button tone="secondary" disabled={ask.isPending} onClick={() => ask.mutate()}>
        {t('Ask VAYORA staff to check it')}
      </Button>
    </div>
  );
}
