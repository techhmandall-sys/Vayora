import { useMutation } from '@tanstack/react-query';

import { api } from '../lib/api';
import { useT } from '../lib/i18n';
import { Button, Notice } from './ui';

/** The server's answer: it names the other side and never carries a phone number. */
interface Relay {
  provider: string;
  calleeName: string;
}

/**
 * A masked call to the other side of a booking (feature 61): through Twilio when the server
 * has an account, simulated otherwise. Either way the answer says which, and carries no number.
 */
export function CallButton({ bookingId, label }: { bookingId: number; label: string }) {
  const t = useT();
  const call = useMutation({ mutationFn: () => api<Relay>(`/bookings/${bookingId}/call`, { method: 'POST' }) });

  return (
    <div className="space-y-2">
      <Button tone="secondary" disabled={call.isPending} onClick={() => call.mutate()}>
        {label}
      </Button>
      {call.data && (
        <Notice tone="info">
          {call.data.provider === 'twilio'
            ? t("Your phone will ring now from VAYORA's number. Answer it to be connected to {name}: neither of you sees the other's number.", { name: call.data.calleeName })
            : t(
            "Calling is simulated. With a real phone service you would now be connected to {name} through a masked number, so neither of you sees the other's number. No call was placed, and {name} has been told you tried.",
            { name: call.data.calleeName },
          )}
        </Notice>
      )}
      {call.error && <Notice tone="error">{call.error.message}</Notice>}
    </div>
  );
}
