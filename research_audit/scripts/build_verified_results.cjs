// Builds research_audit/verified_results.csv from the project's own report files and the
// audit's recomputations. Reads only; writes only the CSV.
//
//   node research_audit/scripts/build_verified_results.cjs <JOURNEX dir> <Vayora-main dir>
//
// Evidence sets:
//   committed_h2   JOURNEX/experiments/reports at git HEAD 30653cf (2026-10-20): what README,
//                  EXPERIMENT_PROTOCOL and AI_ML_METHODOLOGY quote. Model h2 (h1 for run ...001).
//   regenerated_h3 Vayora-main/experiments/reports, rebuilt 2026-10-28 under the last-mile
//                  world and model h3. Not yet committed anywhere.
//   audit_h3       recomputed by the audit from the raw event log of the regenerated runs
//                  (research_audit/data/audit_*.json).
const fs = require('fs');
const path = require('path');

const [journex, vayora] = process.argv.slice(2);
const sets = {
  committed_h2: path.join(journex, 'experiments', 'reports'),
  regenerated_h3: path.join(vayora, 'experiments', 'reports'),
};
const LADDER = '7a1c0de0-0000-4000-8000-000000000003';
const ROBUST = '7a1c0de0-0000-4000-8000-000000000004';
// Run on 2026-10-29 under new run ids (REPRODUCIBILITY_NEXT_STEPS.md §3); nothing overwritten.
// Run ...005 was interrupted when Docker stopped (B3-KM reached 17 of 30 seeds); it has no
// report, is not used, and was left in place rather than deleted. E1 was rerun as ...010.
const NEW_RUNS = [
  ['7a1c0de0-0000-4000-8000-000000000010', 'E1 earliest departure'],
  ['7a1c0de0-0000-4000-8000-000000000006', 'E3b cancellation scale x1.5'],
  ['7a1c0de0-0000-4000-8000-000000000007', 'E3b cancellation scale x2'],
  ['7a1c0de0-0000-4000-8000-000000000008', 'E3b cancellation scale x3'],
  ['7a1c0de0-0000-4000-8000-000000000009', 'E3c last-mile off (world h2 under h3 code)'],
];
const rows = [];
const fmt = (x, d = 4) => (x === null || x === undefined || Number.isNaN(x) ? '' : Number(x).toFixed(d));

function add(r) {
  rows.push({
    result_id: `R${String(rows.length + 1).padStart(3, '0')}`,
    evidence_set: r.set, area: r.area, measure: r.measure, comparison: r.comparison,
    baseline_value: fmt(r.base), variant_value: fmt(r.value), difference: fmt(r.diff),
    ci_low: fmt(r.lo), ci_high: fmt(r.hi), ci_method: r.ciMethod || '', p_value: r.p === undefined ? '' : Number(r.p).toExponential(2),
    p_method: r.pMethod || '', holm_p: r.holm === undefined ? '' : Number(r.holm).toExponential(2),
    seeds_up_down: r.updown || '', n: r.n === undefined ? '' : r.n, source_file: r.source, verification: r.verification, notes: r.notes || '',
  });
}

const read = (set, file) => {
  const p = path.join(sets[set], file);
  return fs.existsSync(p) ? JSON.parse(fs.readFileSync(p, 'utf8')) : null;
};
const relSource = (set, file) => (set === 'committed_h2' ? 'JOURNEX/experiments/reports/' : 'Vayora-main/experiments/reports/') + file;

// Ladder and robustness: primary metric and late share, every variant against the run's baseline.
for (const set of Object.keys(sets)) {
  for (const [run, area] of [[LADDER, 'ladder'], [ROBUST, 'robustness']]) {
    const file = `ladder-${run}.json`;
    const j = read(set, file);
    if (!j) continue;
    for (const [name, v] of Object.entries(j.variants)) {
      for (const measure of ['per_100', 'late_share', 'refilled', 'refills_only_option', 'empty_share', 'early_refill_share', 'late_refill_share']) {
        const cell = v[measure];
        if (!cell) continue;
        const d = cell.vs_baseline;
        const verified = set === 'regenerated_h3' && measure === 'per_100' && area === 'ladder'
          ? 'read from report; variant mean recomputed by audit from raw events and matches'
          : set === 'regenerated_h3' ? 'read from report (not independently recomputed except per_100 contrasts in audit_h3 rows)'
            : 'read from committed report; underlying events overwritten in local DB by 2026-10-28 regeneration; reproducible from JOURNEX HEAD';
        add({
          set, area, measure: cell.label || measure, comparison: d ? `${name} - ${j.baseline}` : `${name} (level)`,
          base: d ? cell.mean - d.mean_difference : undefined, value: cell.mean, diff: d ? d.mean_difference : undefined,
          lo: d?.ci_low, hi: d?.ci_high, ciMethod: d ? 'paired t, 95%' : '', p: d?.wilcoxon_p, pMethod: d ? 'Wilcoxon signed-rank, unadjusted' : '',
          updown: d ? `${d.seeds_improved}/${d.seeds_worsened}` : '', n: cell.seeds, source: relSource(set, file), verification: verified,
          notes: measure === 'late_share' ? 'includes cancellations caused by driver withdrawal (metrics.sql late_cancellation_share)' : '',
        });
      }
    }
  }
  // Sweeps: primary metric per cell against the sweep's baseline.
  for (const file of fs.readdirSync(sets[set]).filter((f) => /^sweep-.*\.json$/.test(f))) {
    const j = read(set, file);
    const history = /-00000000000[12]\.json$/.test(file);
    // History runs were never rebuilt: the same file sits in both folders. List it once.
    if (history && set !== 'committed_h2') continue;
    for (const cell of j.cells) {
      const m = cell.measures.per_100;
      if (!m) continue;
      const d = m.vs_baseline;
      add({
        set: history ? 'history_pre_h2' : /-000000000009\.json$/.test(file) ? 'new_2026_10_29' : set,
        area: `sweep:${j.name}`, measure: 'Completed rides per 100 searches',
        comparison: `${Object.entries(cell.parameters).map(([k, v]) => `${k.split('.').pop()}=${v}`).join(', ')} vs ${j.baseline}${j.baseline_per_cell ? ' (same cell)' : ' (unmodified world)'}`,
        base: m.baseline_mean, value: m.mean, diff: d.mean_difference, lo: d.ci_low, hi: d.ci_high, ciMethod: 'paired t, 95%',
        p: d.wilcoxon_p, pMethod: 'Wilcoxon signed-rank, unadjusted', updown: `${d.seeds_improved}/${d.seeds_worsened}`, n: d.seeds,
        source: relSource(set, file),
        verification: history ? 'HISTORY run made with earlier code/world; not rebuilt by reproduce.sh; not comparable with current world'
          : set === 'committed_h2' ? 'read from committed report' : 'read from regenerated report',
      });
    }
  }
  // Durability model.
  for (const version of set === 'committed_h2' ? ['h1', 'h2'] : ['h3']) {
    const file = `hazard-${version}.json`;
    const j = read(set, file);
    if (!j) continue;
    for (const m of j.models) {
      add({
        set, area: `model:${version}`, measure: `${m.model}: Harrell concordance (validation seeds)`, comparison: 'level',
        value: m.concordance, ciMethod: 'none reported', n: j.validation.bookings, source: relSource(set, file),
        verification: 'read from report', notes: `departure Brier ${fmt(m.departure.brier_score)}; IBS ${fmt(m.integrated_brier_score)}; validation cancellations ${j.validation.cancelled_by_passenger}`,
      });
    }
  }
}

// New experiments: primary metric and late share, every variant against the run's baseline.
for (const [run, area] of NEW_RUNS) {
  const file = `ladder-${run}.json`;
  const j = read('regenerated_h3', file);
  if (!j) continue;
  for (const [name, v] of Object.entries(j.variants)) {
    for (const measure of ['per_100', 'late_share', 'empty_share']) {
      const cell = v[measure];
      const d = cell.vs_baseline;
      add({
        set: 'new_2026_10_29', area, measure: cell.label, comparison: d ? `${name} - ${j.baseline}` : `${name} (level)`,
        base: d ? cell.mean - d.mean_difference : undefined, value: cell.mean, diff: d ? d.mean_difference : undefined,
        lo: d?.ci_low, hi: d?.ci_high, ciMethod: d ? 'paired t, 95%' : '', p: d?.wilcoxon_p, pMethod: d ? 'Wilcoxon signed-rank, unadjusted' : '',
        updown: d ? `${d.seeds_improved}/${d.seeds_worsened}` : '', n: cell.seeds, source: relSource('regenerated_h3', file),
        verification: 'read from report written 2026-10-29 by experiments.analysis.compare',
      });
    }
  }
}
for (const [file, area] of [['truth_recovery.json', 'E3a truth recovery'], ['cancellation_benchmark.json', 'E3b/E3c cancellation benchmark']]) {
  const p = path.join(vayora, 'research_audit', 'data', file);
  if (!fs.existsSync(p)) continue;
  const j = JSON.parse(fs.readFileSync(p, 'utf8'));
  if (Array.isArray(j)) {
    for (const row of j) {
      add({ set: 'new_2026_10_29', area, measure: 'Bookings standing 6 days out still standing at departure', comparison: row.set,
        value: row.survival_from_day_6, n: row.accepted, source: 'research_audit/data/' + file,
        verification: 'computed by audit script (read-only SQL, same query and pooling as experiments/hotel/report.py)',
        notes: `daily cancellation rate ${fmt(100 * row.daily_rate_min, 2)}% to ${fmt(100 * row.daily_rate_max, 2)}%` });
    }
  } else {
    for (const [name, m] of Object.entries(j.models)) {
      add({ set: 'new_2026_10_29', area, measure: `${name}: AUC for lasting to departure (validation seeds)`, comparison: 'level',
        value: m.auc_lasting, n: j.known_outcome, source: 'research_audit/data/' + file,
        verification: 'computed by audit script (in-memory re-simulation of validation seeds)',
        notes: `mean survival ${fmt(m.mean_survival, 3)}; Brier ${fmt(m.brier)}` + (m.share_of_achievable_auc === undefined ? '; true = generating survival (ceiling)'
          : `; share of achievable AUC ${fmt(m.share_of_achievable_auc, 2)}; Spearman vs truth ${fmt(m.spearman_vs_truth, 3)}`) });
    }
  }
}

// Hotel survival models (real data, unchanged between sets).
{
  const file = 'hotel-survival.json';
  const j = read('regenerated_h3', file);
  for (const m of j.models) {
    add({
      set: 'regenerated_h3', area: 'hotel (real data)', measure: `${m.model}: Harrell concordance (2017 arrivals)`, comparison: 'level',
      value: m.concordance, ciMethod: 'none reported', source: 'Vayora-main/experiments/reports/hotel-survival.json',
      verification: 'read from report; byte-identical to committed JOURNEX copy',
      notes: 'train 2015-2016 arrivals, validate 2017 arrivals; 5,821 validation bookings, 435 cancelled (EXPERIMENT_PROTOCOL.md s.19)',
    });
  }
}

// Audit recomputations from raw events (regenerated runs).
const stats = JSON.parse(fs.readFileSync(path.join(vayora, 'research_audit', 'data', 'audit_stats.json'), 'utf8'));
const seats = JSON.parse(fs.readFileSync(path.join(vayora, 'research_audit', 'data', 'audit_seats.json'), 'utf8'));
const auditRow = (r, area, extra = '') => add({
  set: 'audit_h3', area, measure: r.measure === 'per_100' ? 'Completed bookings per 100 searches (primary metric)' : r.measure,
  comparison: `${r.variant} - ${r.baseline}`, base: r.baseline_mean, value: r.variant_mean, diff: r.mean_difference,
  lo: r.bootstrap_ci[0], hi: r.bootstrap_ci[1], ciMethod: 'percentile bootstrap, 20,000 resamples, 95%', p: r.wilcoxon_p,
  pMethod: 'Wilcoxon signed-rank', holm: r.holm_p, updown: `${r.seeds_up}/${r.seeds_down}`, n: r.seeds,
  source: 'research_audit/data/audit_' + (area.includes('seat') && area.includes('robust') ? 'seats' : 'stats') + '.json',
  verification: 'recomputed by audit from raw event log (read-only SQL)',
  notes: `t CI [${fmt(r.t_ci[0])}, ${fmt(r.t_ci[1])}]; d_z ${fmt(r.d_z, 2)}; MDE(80% power) ${fmt(r.mde_80)}; relative ${fmt(r.relative_difference_pct, 2)}%${extra}`,
});
for (const r of stats.primary_contrasts) auditRow(r, r.run === LADDER ? 'ladder (audit)' : 'robustness (audit)', '; Holm over the 13-contrast family');
for (const r of stats.seat_weighted) auditRow(r, 'ladder seat-weighted (audit, EXPLORATORY)', '; post hoc metric, not pre-specified');
for (const r of stats.late_share) auditRow(r, 'ladder late share (audit)', '');
for (const r of seats.robustness_seat_weighted_vs_B3) auditRow(r, 'robustness seat-weighted (audit, EXPLORATORY)', '; Holm over 7 contrasts; post hoc');

const columns = Object.keys(rows[0]);
const csv = [columns.join(','), ...rows.map((r) => columns.map((c) => {
  const v = String(r[c]);
  return /[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
}).join(','))].join('\n') + '\n';
fs.writeFileSync(path.join(vayora, 'research_audit', 'verified_results.csv'), csv, 'utf8');
console.log(`${rows.length} rows`);
