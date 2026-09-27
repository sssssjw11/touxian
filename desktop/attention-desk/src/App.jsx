import { useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  Archive,
  ArrowRight,
  ArrowUpRight,
  Brain,
  CalendarRange,
  Check,
  ChevronDown,
  CircleHelp,
  Clock3,
  Database,
  FileJson,
  FileText,
  Filter,
  Globe2,
  Inbox,
  LayoutPanelTop,
  LoaderCircle,
  Mail,
  MessageCircle,
  PanelRight,
  RefreshCw,
  RotateCcw,
  Search,
  Send,
  Settings2,
  SlidersHorizontal,
  Sparkles,
  ShieldCheck,
  UserRound,
  Users,
  Upload,
  X,
} from 'lucide-react';
import OfficialMonitor from './OfficialMonitor';

const CATEGORY_LABELS = {
  course: '课程安排',
  assignment: '材料提交',
  exam: '考试测验',
  activity: '活动会议',
  admin: '行政手续',
  safety: '安全提醒',
  employment: '实习就业',
  resource: '资料资源',
  noise: '群聊噪声',
  other: '其他事项',
};

const RECORD_KIND_LABELS = {
  announcement: '公告事项',
  resource: '资料 / 技术分享',
  conversation: '普通对话',
  noise: '群聊噪声',
  other: '待确认事项',
};

const PRIORITY_META = {
  P0: { label: '马上处理', className: 'p0' },
  P1: { label: '近期处理', className: 'p1' },
  P2: { label: '排进日程', className: 'p2' },
  P3: { label: '留作参考', className: 'p3' },
};

const DEADLINE_META = {
  overdue: { label: '已超期', className: 'overdue' },
  due_today: { label: '今日截止', className: 'today' },
  near: { label: '临近', className: 'near' },
  ample: { label: '宽裕', className: 'ample' },
  unscheduled: { label: '未排期', className: 'unscheduled' },
  reference: { label: '日期参考', className: 'reference' },
  unknown: { label: '待确认', className: 'unknown' },
};

const ACTIVE_DEADLINE_FILTERS = [
  { id: 'all', label: '全部' },
  { id: 'due_today', label: '今日截止' },
  { id: 'near', label: '临近 1–3 天' },
  { id: 'ample', label: '宽裕 4 天+' },
  { id: 'unscheduled', label: '未排期' },
];

const ARCHIVE_FILTERS = [
  { id: 'all', label: '全部归档' },
  { id: 'completed', label: '已完成' },
  { id: 'deadline_passed', label: '已超期' },
];

const defaultAsOf = formatDateInput(new Date());

const QUICK_RANGE_OPTIONS = [
  { id: 'day', label: '最近 1 天' },
  { id: 'week', label: '最近 7 天' },
  { id: 'calendar-week', label: '本周' },
  { id: 'all', label: '全部' },
];

function parseDateInput(value) {
  const [year, month, day] = String(value || '').split('-').map(Number);
  if (!year || !month || !day) return new Date();
  return new Date(year, month - 1, day);
}

function formatDateInput(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function shiftDate(value, offset) {
  const date = parseDateInput(value);
  date.setDate(date.getDate() + offset);
  return formatDateInput(date);
}

function getQuickRange(anchor, id) {
  if (id === 'day') return { from: anchor, to: anchor };
  if (id === 'week') return { from: shiftDate(anchor, -6), to: anchor };
  if (id === 'calendar-week') {
    const date = parseDateInput(anchor);
    const day = date.getDay();
    date.setDate(date.getDate() + (day === 0 ? -6 : 1 - day));
    return { from: formatDateInput(date), to: anchor };
  }
  return { from: '', to: '' };
}

function formatRangeLabel(from, to) {
  if (from && to) return `${from} 至 ${to}`;
  if (from) return `${from} 起`;
  if (to) return `截至 ${to}`;
  return '完整归档';
}

function formatNumber(value) {
  return new Intl.NumberFormat('zh-CN').format(value || 0);
}

function formatPercent(value) {
  return Math.round((value || 0) * 100) + '%';
}

function shortDate(value) {
  if (!value) return '未识别 DDL';
  if (value.includes('T')) return value.replace('T', ' ').slice(0, 16);
  return value;
}

function categoryLabel(value) {
  return CATEGORY_LABELS[value] || value || '未分类';
}

function recordKindLabel(value) {
  return RECORD_KIND_LABELS[value] || value || '未确认';
}

function itemTitle(item) {
  const text = item && item.preview ? item.preview : '未命名事项';
  return text.split(/\n|。|！|!/)[0].trim().slice(0, 58) || text.slice(0, 58);
}

function deadlineText(item) {
  const deadline = item && item.judgments && item.judgments.deadline && item.judgments.deadline.value;
  if (!deadline) return '没有明确 DDL';
  if (deadline.normalized) return shortDate(deadline.normalized);
  return deadline.raw ? '原文：' + deadline.raw.slice(0, 24) : '日期待复核';
}

function deadlineStatus(item) {
  const deadline = item && item.judgments && item.judgments.deadline && item.judgments.deadline.value;
  const decided = item && item.decision && item.decision.deadline_status;
  if (decided && decided !== 'upcoming') return decided;
  if (!deadline) return 'unscheduled';
  if (deadline.kind && deadline.kind !== 'deadline') return 'reference';
  const days = Number(deadline.days_from_as_of);
  if (!Number.isFinite(days)) return 'unknown';
  if (days < 0) return 'overdue';
  if (days === 0) return 'due_today';
  if (days <= 3) return 'near';
  return 'ample';
}

function queueState(item) {
  return (item && item.decision && item.decision.queue_state) || (deadlineStatus(item) === 'overdue' ? 'archived' : 'active');
}

function archiveReason(item) {
  if (queueState(item) !== 'archived') return null;
  return (item && item.decision && item.decision.archive_reason) || 'deadline_passed';
}

function sortByQueueState(items) {
  const active = [];
  const completed = [];
  const overdue = [];
  items.forEach((item) => {
    if (queueState(item) === 'active') active.push(item);
    else if (archiveReason(item) === 'completed') completed.push(item);
    else overdue.push(item);
  });
  completed.sort((left, right) => String(right.decision.completed_at || '').localeCompare(String(left.decision.completed_at || '')));
  return [...active, ...completed, ...overdue];
}

function withItemDecision(result, key, decision) {
  if (!result) return result;
  const current = result.items.find((item) => item.item_key === key);
  if (!current) return result;
  const updated = { ...current, decision };
  const activeDelta = Number(queueState(updated) === 'active') - Number(queueState(current) === 'active');
  const completedDelta = Number(archiveReason(updated) === 'completed') - Number(archiveReason(current) === 'completed');
  const overdueDelta = Number(archiveReason(updated) === 'deadline_passed') - Number(archiveReason(current) === 'deadline_passed');
  const summary = result.summary || {};
  return {
    ...result,
    items: sortByQueueState(result.items.map((item) => item.item_key === key ? updated : item)),
    summary: {
      ...summary,
      active_count: Number(summary.active_count || 0) + activeDelta,
      archived_count: Number(summary.archived_count || 0) - activeDelta,
      completed_count: Number(summary.completed_count || 0) + completedDelta,
      overdue_count: Number(summary.overdue_count || 0) + overdueDelta,
      queue_state_counts: {
        ...summary.queue_state_counts,
        active: Number(summary.queue_state_counts?.active || 0) + activeDelta,
        archived: Number(summary.queue_state_counts?.archived || 0) - activeDelta,
      },
      archive_reason_counts: {
        ...summary.archive_reason_counts,
        completed: Number(summary.archive_reason_counts?.completed || 0) + completedDelta,
        deadline_passed: Number(summary.archive_reason_counts?.deadline_passed || 0) + overdueDelta,
      },
      needs_review_count: Number(summary.needs_review_count || 0) + (current.decision && current.decision.needs_review ? activeDelta : 0),
    },
  };
}

function deadlineRelativeText(item) {
  const deadline = item && item.judgments && item.judgments.deadline && item.judgments.deadline.value;
  const status = deadlineStatus(item);
  const days = deadline && Number(deadline.days_from_as_of);
  if (status === 'overdue' && Number.isFinite(days)) return `超期 ${Math.abs(days)} 天`;
  if (status === 'due_today') return '今天截止';
  if ((status === 'near' || status === 'ample') && Number.isFinite(days)) return `剩余 ${days} 天`;
  return (DEADLINE_META[status] || DEADLINE_META.unknown).label;
}

function matchesDeadlineFilter(item, filter) {
  const status = deadlineStatus(item);
  if (filter === 'all') return true;
  if (filter === 'unscheduled') return ['unscheduled', 'reference', 'unknown'].includes(status);
  return status === filter;
}

function confidence(item) {
  const values = Object.values((item && item.judgments) || {})
    .map((value) => Number(value && value.confidence))
    .filter((value) => Number.isFinite(value));
  if (!values.length) return 0;
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function getFilterCount(items, filter) {
  if (filter === 'all') return items.length;
  return items.filter((item) => item.decision && item.decision.priority === filter).length;
}

function DecisionRow({ label, type, value, confidence: score, tone = 'neutral' }) {
  return (
    <div className="decision-row">
      <div className="decision-name">
        <span className={'type-mark ' + tone}>{type}</span>
        <span>{label}</span>
      </div>
      <strong>{value}</strong>
      <div className="confidence-track" aria-label={'置信度 ' + formatPercent(score)}>
        <span style={{ width: Math.max(4, Math.min(100, (score || 0) * 100)) + '%' }} />
      </div>
      <span className="confidence-value">{formatPercent(score)}</span>
    </div>
  );
}

function PriorityPill({ priority }) {
  const meta = PRIORITY_META[priority] || PRIORITY_META.P3;
  return <span className={'priority-pill ' + meta.className}>{priority}</span>;
}

function DeadlinePill({ item, compact = false, archived = false }) {
  const status = deadlineStatus(item);
  const meta = DEADLINE_META[status] || DEADLINE_META.unknown;
  return (
    <span className={'deadline-pill ' + meta.className + (archived ? ' archived-deadline' : '')} title={deadlineText(item)}>
      {compact ? meta.label : deadlineRelativeText(item)}
    </span>
  );
}

function ArchivePill({ item }) {
  const completed = archiveReason(item) === 'completed';
  return <span className={'archive-pill ' + (completed ? 'completed' : '')}>{completed ? '已完成' : '超期归档'}</span>;
}

function EmptyState({ onUpload }) {
  return (
    <div className="empty-state">
      <FileJson size={34} strokeWidth={1.4} />
      <h3>还没有载入群聊</h3>
      <p>选择一个导出的 messages.json，或者直接打开示例群聊。</p>
      <button className="button button-primary" onClick={onUpload}>
        <Upload size={16} /> 选择文件
      </button>
    </div>
  );
}

function RangeEmptyState({ dateRangeLabel, archiveCount, onReset, onOpenSettings }) {
  return (
    <section className="range-empty-state">
      <div className="range-empty-icon"><CalendarRange size={28} strokeWidth={1.5} /></div>
      <p className="eyebrow">NO MESSAGES IN RANGE</p>
      <h2>这个日期范围没有消息</h2>
      <p>当前选定的消息范围为 <strong>{dateRangeLabel}</strong>，没有可供分拣的聊天记录。完整归档共有 {formatNumber(archiveCount)} 条消息。</p>
      <div className="range-empty-actions">
        <button className="button button-primary" onClick={onReset}><RefreshCw size={16} /> 回到完整归档</button>
        <button className="button button-quiet" onClick={onOpenSettings}><SlidersHorizontal size={16} /> 调整范围</button>
      </div>
    </section>
  );
}

function App() {
  const [screen, setScreen] = useState(() => window.localStorage.getItem('attention-screen') === 'official' ? 'official' : 'chat');
  const [result, setResult] = useState(null);
  const [selectedId, setSelectedId] = useState(null);
  const [filter, setFilter] = useState('all');
  const [queueView, setQueueView] = useState('active');
  const [archiveFilter, setArchiveFilter] = useState('all');
  const [deadlineFilter, setDeadlineFilter] = useState('all');
  const [visibleCount, setVisibleCount] = useState(160);
  const [provider, setProvider] = useState('local');
  const [endpoint, setEndpoint] = useState('https://api.typesafe.ai/v1/systemone');
  const [model, setModel] = useState('jev-system-one');
  const [apiKey, setApiKey] = useState('');
  const [maxCandidates, setMaxCandidates] = useState('48');
  const [asOf, setAsOf] = useState(defaultAsOf);
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [file, setFile] = useState(null);
  const [archiveFile, setArchiveFile] = useState(null);
  const [sourceMode, setSourceMode] = useState('file');
  const [inboxItems, setInboxItems] = useState([]);
  const [inboxStatus, setInboxStatus] = useState(null);
  const [selectedInbox, setSelectedInbox] = useState(null);
  const [inboxLoading, setInboxLoading] = useState(false);
  const [wechatStatus, setWechatStatus] = useState(null);
  const [wechatGroups, setWechatGroups] = useState([]);
  const [wechatQuery, setWechatQuery] = useState('');
  const [selectedGroup, setSelectedGroup] = useState(null);
  const [resolveWechatFiles, setResolveWechatFiles] = useState(false);
  const [wechatLoading, setWechatLoading] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState('');
  const [query, setQuery] = useState('');
  const [reviewOnly, setReviewOnly] = useState(false);
  const [categoryFilter, setCategoryFilter] = useState('all');
  const [completionPending, setCompletionPending] = useState(() => new Set());
  // 邮件摘要：地址持久化到 localStorage，确认令牌只在内存里存一份
  const [mailStatus, setMailStatus] = useState(null);
  const [mailOpen, setMailOpen] = useState(false);
  const [mailRecipients, setMailRecipients] = useState(() => window.localStorage.getItem('attention-mail-recipients') || '');
  const [mailFormat, setMailFormat] = useState(() => window.localStorage.getItem('attention-mail-format') || 'html');
  const [mailPreview, setMailPreview] = useState(null);
  const [mailBusy, setMailBusy] = useState(false);
  const [mailNotice, setMailNotice] = useState('');
  const [mailSent, setMailSent] = useState(null);
  // 用户画像：默认关闭。开启后进入判断链路的 relevance 加权，只落本机文件。
  const [profileOpen, setProfileOpen] = useState(false);
  const [profile, setProfile] = useState(() => ({
    enabled: false,
    college: '',
    major: '',
    grade: '',
    notes: '',
    interests: [],
  }));
  const [profileMeta, setProfileMeta] = useState({ colleges: [], grade_range: [2019, 2030], limits: {} });
  const [profileActive, setProfileActive] = useState(false);
  const [profileBusy, setProfileBusy] = useState(false);
  const [profileNotice, setProfileNotice] = useState('');
  const [interestDraft, setInterestDraft] = useState('');
  const fileRef = useRef(null);
  const archiveRef = useRef(null);

  useEffect(() => {
    window.localStorage.setItem('attention-screen', screen);
  }, [screen]);

  const loadProfile = async () => {
    try {
      const response = await fetch('/api/profile');
      const payload = await response.json();
      if (!response.ok) throw new Error(payload.detail || '无法读取画像');
      setProfile(payload.profile || {});
      setProfileMeta({
        colleges: payload.colleges || [],
        grade_range: payload.grade_range || [2019, 2030],
        limits: payload.limits || {},
      });
      setProfileActive(Boolean(payload.active));
    } catch (err) {
      setError(err.message);
    }
  };

  const saveProfile = async (next = profile) => {
    setProfileBusy(true);
    setProfileNotice('');
    try {
      const response = await fetch('/api/profile', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(next),
      });
      const payload = await response.json();
      if (!response.ok) throw new Error(payload.detail || '保存失败');
      setProfile(payload.profile || {});
      setProfileActive(Boolean(payload.active));
      setProfileNotice(payload.active ? '已保存，下次分拣时生效' : '已保存（未启用）');
    } catch (err) {
      setProfileNotice(err.message);
    } finally {
      setProfileBusy(false);
    }
  };

  const patchProfile = (patch) => setProfile((current) => ({ ...current, ...patch }));

  const addInterest = () => {
    const text = interestDraft.trim();
    if (!text) return;
    setProfile((current) => {
      const interests = current.interests || [];
      if (interests.includes(text)) return current;
      return { ...current, interests: [...interests, text] };
    });
    setInterestDraft('');
  };

  const removeInterest = (value) => {
    setProfile((current) => ({ ...current, interests: (current.interests || []).filter((item) => item !== value) }));
  };

  const loadWechatGroups = async (query = wechatQuery, signal) => {
    setWechatLoading(true);
    setError('');
    try {
      const statusResponse = await fetch('/api/wechat/status', { signal });
      const statusPayload = await statusResponse.json();
      if (!statusResponse.ok) throw new Error(statusPayload.detail || '无法读取微信状态');
      setWechatStatus(statusPayload);
      if (!statusPayload.ready) {
        setWechatGroups([]);
        return;
      }
      const groupsResponse = await fetch('/api/wechat/groups?q=' + encodeURIComponent(query || '') + '&limit=80', { signal });
      const groupsPayload = await groupsResponse.json();
      if (!groupsResponse.ok) throw new Error(groupsPayload.detail || '无法读取微信群列表');
      setWechatGroups(groupsPayload.groups || []);
    } catch (err) {
      if (err.name !== 'AbortError') setError(err.message);
    } finally {
      if (!signal || !signal.aborted) setWechatLoading(false);
    }
  };

  const loadSample = async (targetDate = asOf, targetFromDate = fromDate, targetToDate = toDate) => {
    setLoading(true);
    setError('');
    try {
      const params = new URLSearchParams({ as_of: targetDate });
      if (targetFromDate) params.set('from_date', targetFromDate);
      if (targetToDate) params.set('to_date', targetToDate);
      const response = await fetch('/api/sample?' + params.toString());
      const payload = await response.json();
      if (!response.ok) throw new Error(payload.detail || '示例加载失败');
      setResult(payload);
      setSelectedId(payload.items && payload.items.length ? payload.items[0].candidate_id : null);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadSample();
  }, []);

  useEffect(() => {
    window.localStorage.setItem('attention-mail-recipients', mailRecipients);
  }, [mailRecipients]);

  useEffect(() => {
    window.localStorage.setItem('attention-mail-format', mailFormat);
  }, [mailFormat]);

  useEffect(() => {
    let alive = true;
    fetch('/api/mail/status')
      .then((response) => response.json())
      .then((payload) => { if (alive) setMailStatus(payload); })
      .catch(() => { if (alive) setMailStatus({ ready: false, reason: '无法连接后端服务' }); });
    return () => { alive = false; };
  }, []);

  useEffect(() => {
    loadProfile();
  }, []);

  useEffect(() => {
    if (!settingsOpen || sourceMode !== 'wechat') return undefined;
    const controller = new AbortController();
    const timer = window.setTimeout(() => loadWechatGroups(wechatQuery, controller.signal), 220);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [settingsOpen, sourceMode, wechatQuery]);

  const loadInbox = async (signal) => {
    setInboxLoading(true);
    try {
      const response = await fetch('/api/inbox', { signal });
      const payload = await response.json();
      setInboxItems(payload.items || []);
      setInboxStatus(payload.status || null);
    } catch (err) {
      if (err && err.name === 'AbortError') return;
      setInboxStatus({ watch_dir_exists: false });
    } finally {
      setInboxLoading(false);
    }
  };

  useEffect(() => {
    if (!settingsOpen || sourceMode !== 'inbox') return undefined;
    const controller = new AbortController();
    loadInbox(controller.signal);
    // 面板打开期间轮询，微信转发后几秒内自动出现在列表里
    const timer = window.setInterval(() => {
      if (!document.hidden) loadInbox();
    }, 10000);
    return () => {
      controller.abort();
      window.clearInterval(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settingsOpen, sourceMode]);

  const runAnalysis = async (overrides = {}) => {
    const effectiveAsOf = overrides.asOf ?? asOf;
    const effectiveFromDate = overrides.fromDate ?? fromDate;
    const effectiveToDate = overrides.toDate ?? toDate;
    setRunning(true);
    setError('');
    if (effectiveFromDate && effectiveToDate && effectiveFromDate > effectiveToDate) {
      setError('开始日期不能晚于结束日期');
      setRunning(false);
      return;
    }
    let request;
    if (sourceMode === 'wechat') {
      if (!selectedGroup) {
        setError('请先选择一个微信群');
        setRunning(false);
        return;
      }
      request = fetch('/api/wechat/analyze', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          username: selectedGroup.username,
          display_name: selectedGroup.display_name,
          provider,
          api_key: apiKey,
          endpoint,
          model,
          as_of: effectiveAsOf,
          from_date: effectiveFromDate,
          to_date: effectiveToDate,
          max_candidates: maxCandidates,
          resolve_files: resolveWechatFiles,
        }),
      });
    } else if (sourceMode === 'inbox') {
      if (!selectedInbox) {
        setError('请先从转发收件箱选择一条聊天记录');
        setRunning(false);
        return;
      }
      request = fetch('/api/inbox/import', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          filename: selectedInbox.filename,
          provider,
          api_key: apiKey,
          endpoint,
          model,
          as_of: effectiveAsOf,
          from_date: effectiveFromDate,
          to_date: effectiveToDate,
          max_candidates: maxCandidates,
        }),
      });
    } else if (sourceMode === 'archive') {
      if (!archiveFile) {
        setError('请先选择微信导出的聊天记录压缩包');
        setRunning(false);
        return;
      }
      const form = new FormData();
      form.append('file', archiveFile);
      form.append('provider', provider);
      form.append('api_key', apiKey);
      form.append('endpoint', endpoint);
      form.append('model', model);
      form.append('as_of', effectiveAsOf);
      form.append('from_date', effectiveFromDate);
      form.append('to_date', effectiveToDate);
      form.append('max_candidates', maxCandidates);
      request = fetch('/api/analyze-archive', { method: 'POST', body: form });
    } else {
      const form = new FormData();
      if (file) {
        form.append('file', file);
        form.append('use_sample', 'false');
      } else {
        form.append('use_sample', 'true');
      }
      form.append('provider', provider);
      form.append('api_key', apiKey);
      form.append('endpoint', endpoint);
      form.append('model', model);
      form.append('as_of', effectiveAsOf);
      form.append('from_date', effectiveFromDate);
      form.append('to_date', effectiveToDate);
      form.append('max_candidates', maxCandidates);
      request = fetch('/api/analyze', { method: 'POST', body: form });
    }
    try {
      const response = await request;
      const payload = await response.json();
      if (!response.ok) throw new Error(payload.detail || '分拣失败');
      setResult(payload);
      if (sourceMode === 'inbox') loadInbox();
      setSelectedId(payload.items && payload.items.length ? payload.items[0].candidate_id : null);
      setFilter('all');
      setQueueView('active');
      setArchiveFilter('all');
      setDeadlineFilter('all');
      setReviewOnly(false);
      setCategoryFilter('all');
      setQuery('');
      setSettingsOpen(false);
    } catch (err) {
      setError(err.message);
    } finally {
      setRunning(false);
    }
  };

  const rangeAnchor = (result && result.source && result.source.archive_end) || asOf || defaultAsOf;

  const applyQuickRange = (id) => {
    const nextRange = getQuickRange(rangeAnchor, id);
    setFromDate(nextRange.from);
    setToDate(nextRange.to);
    runAnalysis({ fromDate: nextRange.from, toDate: nextRange.to });
  };

  const activeItems = useMemo(
    () => ((result && result.items) || []).filter((item) => queueState(item) === 'active'),
    [result],
  );

  const archivedItems = useMemo(
    () => ((result && result.items) || []).filter((item) => queueState(item) === 'archived'),
    [result],
  );

  const deadlineScopedItems = useMemo(
    () => (queueView === 'archive'
      ? archivedItems.filter((item) => archiveFilter === 'all' || archiveReason(item) === archiveFilter)
      : activeItems.filter((item) => matchesDeadlineFilter(item, deadlineFilter))),
    [activeItems, archivedItems, queueView, deadlineFilter, archiveFilter],
  );

  const filteredItems = useMemo(() => {
    const priorityItems = queueView === 'archive' || filter === 'all' ? deadlineScopedItems : deadlineScopedItems.filter((item) => item.decision && item.decision.priority === filter);
    const categoryItems = categoryFilter === 'all'
      ? priorityItems
      : priorityItems.filter((item) => item.judgments && item.judgments.category && item.judgments.category.value === categoryFilter);
    const reviewItems = reviewOnly ? categoryItems.filter((item) => item.decision && item.decision.needs_review) : categoryItems;
    if (!query.trim()) return reviewItems;
    const needle = query.trim().toLowerCase();
    return reviewItems.filter((item) => (item.preview || '').toLowerCase().includes(needle));
  }, [deadlineScopedItems, queueView, filter, categoryFilter, reviewOnly, query]);

  const displayedItems = filteredItems.slice(0, visibleCount);

  useEffect(() => {
    setVisibleCount(160);
  }, [result && result.source, queueView, deadlineFilter, archiveFilter, filter, categoryFilter, reviewOnly, query]);

  useEffect(() => {
    if (!filteredItems.length) {
      if (selectedId !== null) setSelectedId(null);
      return;
    }
    if (!filteredItems.some((item) => item.candidate_id === selectedId)) {
      setSelectedId(filteredItems[0].candidate_id);
    }
  }, [filteredItems, selectedId]);

  const selectedItem = useMemo(
    () => filteredItems.find((item) => item.candidate_id === selectedId) || filteredItems[0] || null,
    [filteredItems, selectedId],
  );

  const onFileChange = (event) => {
    const nextFile = event.target.files && event.target.files[0] ? event.target.files[0] : null;
    setFile(nextFile);
    setSourceMode('file');
    if (nextFile) setSettingsOpen(true);
    event.target.value = '';
  };

  const onArchiveChange = (event) => {
    const nextFile = event.target.files && event.target.files[0] ? event.target.files[0] : null;
    setArchiveFile(nextFile);
    if (nextFile) setSourceMode('archive');
    event.target.value = '';
  };

  // ---------------------------------------------------------------- 邮件摘要

  const openMail = () => {
    setMailOpen(true);
    setMailNotice('');
    setMailSent(null);
    if (!mailPreview) prepareMail();
  };

  const prepareMail = async (format = mailFormat) => {
    if (!result) {
      setMailNotice('请先完成一次分拣，再生成邮件摘要。');
      return;
    }
    setMailBusy(true);
    setMailNotice('');
    setMailSent(null);
    try {
      const response = await fetch('/api/mail/prepare', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          result,
          recipients: mailRecipients,
          as_of: (result.source && result.source.as_of) || asOf,
          body_format: format,
        }),
      });
      const payload = await response.json();
      if (!response.ok) throw new Error(payload.detail || '生成邮件摘要失败');
      setMailPreview(payload);
      if (Array.isArray(payload.recipients) && payload.recipients.length) {
        setMailRecipients(payload.recipients.join(', '));
      }
    } catch (err) {
      setMailPreview(null);
      setMailNotice(err.message);
    } finally {
      setMailBusy(false);
    }
  };

  // 切换格式后旧预览作废（令牌与内容绑定），需要重新生成
  const changeMailFormat = (next) => {
    setMailFormat(next);
    setMailPreview(null);
    setMailNotice('');
    setMailSent(null);
  };

  const confirmMailSend = async () => {
    if (!mailPreview) return;
    setMailBusy(true);
    setMailNotice('');
    try {
      const response = await fetch('/api/mail/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          confirmation_token: mailPreview.confirmation_token,
          recipients: mailPreview.recipients,
          subject: mailPreview.subject,
          body: mailPreview.body,
          body_format: mailPreview.body_format || mailFormat,
        }),
      });
      const payload = await response.json();
      if (!response.ok) throw new Error(payload.detail || '发送失败');
      setMailSent(payload);
      setMailPreview(null);
      setMailNotice('邮件已发出。');
    } catch (err) {
      setMailNotice(err.message);
    } finally {
      setMailBusy(false);
    }
  };

  const archiveStale = result && result.summary && result.summary.archive_stale;
  const messageDateFilter = (result && result.source && result.source.message_date_filter) || {};
  const hasDateFilter = Boolean(messageDateFilter.from || messageDateFilter.to);
  const dateRangeLabel = formatRangeLabel(messageDateFilter.from, messageDateFilter.to);
  const draftDateRangeLabel = formatRangeLabel(fromDate, toDate);
  const providerLabel = provider === 'local' ? '本地 Jev 基线' : provider === 'jev' ? 'TypeSafe Jev' : provider === 'deepseek' ? 'DeepSeek' : '自定义 Jev API';

  // 切换提供方时把 endpoint / model 预置成该家的默认值
  const selectProvider = (next) => {
    setProvider(next);
    if (next === 'deepseek') {
      if (!endpoint || endpoint.includes('typesafe')) setEndpoint('https://api.deepseek.com/chat/completions');
      if (!model || model === 'jev-system-one') setModel('deepseek-flash');
    } else if (next === 'jev') {
      if (!endpoint || endpoint.includes('deepseek')) setEndpoint('https://api.typesafe.ai/v1/systemone');
      if (!model || model === 'deepseek-flash') setModel('jev-system-one');
    }
  };
  const sourceLabel = result && result.source && result.source.source_kind === 'wechat-local'
    ? '本机微信只读导入'
    : result && result.source && result.source.source_kind === 'archive-upload'
      ? '聊天记录压缩包导入'
      : 'messages.json 导入';
  const deadlineStatusCounts = (result && result.summary && result.summary.deadline_status_counts) || {};
  const categoryCounts = (result && result.summary && result.summary.category_counts) || {};
  const categoryOptions = Object.keys(CATEGORY_LABELS).filter((value) => categoryCounts[value]);
  const activeTotal = result && result.summary && Number.isFinite(Number(result.summary.active_count))
    ? Number(result.summary.active_count)
    : activeItems.length;
  const archivedTotal = result && result.summary && Number.isFinite(Number(result.summary.archived_count))
    ? Number(result.summary.archived_count)
    : archivedItems.length;
  const completedTotal = result && result.summary && Number.isFinite(Number(result.summary.completed_count))
    ? Number(result.summary.completed_count)
    : archivedItems.filter((item) => archiveReason(item) === 'completed').length;
  const overdueTotal = result && result.summary && Number.isFinite(Number(result.summary.overdue_count))
    ? Number(result.summary.overdue_count)
    : archivedItems.filter((item) => archiveReason(item) === 'deadline_passed').length;
  const activeUrgentCount = activeItems.filter((item) => ['P0', 'P1'].includes(item.decision && item.decision.priority)).length;
  const viewReviewCount = deadlineScopedItems.filter((item) => item.decision && item.decision.needs_review).length;
  const hasMessages = Boolean(result && result.source && Number(result.source.message_count || 0) > 0);
  const activeQuickRangeId = QUICK_RANGE_OPTIONS.find((option) => {
    const optionRange = getQuickRange(rangeAnchor, option.id);
    return optionRange.from === (messageDateFilter.from || '') && optionRange.to === (messageDateFilter.to || '');
  })?.id || '';
  const queueLimitLabel = queueView === 'archive'
    ? `已归档 ${formatNumber(deadlineScopedItems.length)} / ${formatNumber(archivedTotal)} 条`
    : `进行中 ${formatNumber(activeTotal)} 条`;
  const headline = result
    ? <>进行中 <em>{formatNumber(activeTotal)}</em> 件</>
    : <>正在建立截止矩阵</>;

  const selectQueueView = (view) => {
    setQueueView(view);
    setFilter('all');
    setDeadlineFilter('all');
    setArchiveFilter('all');
    setSelectedId(null);
  };

  const setItemCompleted = async (item, completed) => {
    const key = item.item_key;
    if (!key || completionPending.has(key)) return;
    const before = item.decision;
    const nextDecision = completed
      ? { ...before, queue_state: 'archived', archive_reason: 'completed', completed_at: new Date().toISOString() }
      : { ...before, queue_state: deadlineStatus(item) === 'overdue' ? 'archived' : 'active', archive_reason: deadlineStatus(item) === 'overdue' ? 'deadline_passed' : null, completed_at: null };
    setCompletionPending((previous) => new Set(previous).add(key));
    setError('');
    if (completed && queueView === 'active' && selectedItem && selectedItem.candidate_id === item.candidate_id) {
      const index = filteredItems.findIndex((value) => value.candidate_id === item.candidate_id);
      setSelectedId((filteredItems[index + 1] || filteredItems[index - 1] || {}).candidate_id || null);
    }
    setResult((previous) => withItemDecision(previous, key, nextDecision));
    try {
      const response = await fetch('/api/items/completion', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ item_key: key, completed }),
      });
      const payload = await response.json();
      if (!response.ok) throw new Error(payload.detail || '保存完成状态失败');
      if (completed) {
        setResult((previous) => withItemDecision(previous, key, { ...nextDecision, completed_at: payload.completed_at }));
      }
    } catch (err) {
      setResult((previous) => withItemDecision(previous, key, before));
      setError(err.message);
    } finally {
      setCompletionPending((previous) => {
        const next = new Set(previous);
        next.delete(key);
        return next;
      });
    }
  };

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand-lockup" title="Attention Desk">
          <div className="brand-mark"><img src="/touxian-icon.png" alt="" /></div>
          <span>AD</span>
        </div>
        <nav className="side-nav" aria-label="主导航">
          <button className={'nav-icon ' + (screen === 'chat' ? 'active' : '')} title="微信群通知" aria-label="微信群通知" onClick={() => setScreen('chat')}><Inbox size={19} /></button>
          <button className={'nav-icon ' + (screen === 'official' ? 'active' : '')} title="学院官网监测" aria-label="学院官网监测" onClick={() => { setSettingsOpen(false); setScreen('official'); }}><Globe2 size={19} /></button>
        </nav>
        <div className="side-bottom">
          <a className="nav-icon" title="项目说明" href="https://github.com/sssssjw11/wzu-notice-scraper#readme" target="_blank" rel="noopener noreferrer"><CircleHelp size={19} /></a>
          {screen === 'chat' && (
            <button className={'nav-icon profile-nav ' + (profileOpen ? 'active' : '')} title="我的画像" onClick={() => setProfileOpen(true)}>
              <UserRound size={19} />
              {profileActive && <span className="nav-badge" />}
            </button>
          )}
          {screen === 'chat' && <button className={'nav-icon ' + (settingsOpen ? 'active' : '')} title="API 设置" onClick={() => setSettingsOpen(true)}><Settings2 size={19} /></button>}
        </div>
      </aside>

      <main className="main-canvas">
        {screen === 'official' ? <OfficialMonitor /> : <>
        <header className="topbar">
          <div className="group-context">
            <div className="context-icon"><MessageCircle size={17} /></div>
            <div>
              <span className="context-label">正在查看</span>
              <strong>{(result && result.source && result.source.conversation) || '上传群聊'}</strong>
            </div>
            <ChevronDown size={15} className="muted-icon" />
          </div>
          <div className="topbar-actions">
            <label className="date-control" title="分析基准日">
              <Clock3 size={15} />
              <span className="date-control-label">判断日</span>
              <input type="date" value={asOf} onChange={(event) => setAsOf(event.target.value)} />
            </label>
            <button className="provider-button" onClick={() => setSettingsOpen(true)}>
              <span className={'status-dot ' + (provider === 'local' ? 'local' : 'remote')} />
              {providerLabel}
              <ChevronDown size={14} />
            </button>
            <button
              className="button button-quiet mail-trigger"
              title={mailStatus && mailStatus.ready ? `发送待办摘要到 ${mailStatus.sender}` : '邮件链路未就绪'}
              onClick={openMail}
              disabled={!result || (mailStatus && !mailStatus.ready)}
            >
              <Mail size={16} /> 邮件摘要
            </button>
            <button className="button button-quiet" title="上传 messages.json" onClick={() => fileRef.current && fileRef.current.click()}>
              <Upload size={16} /> 上传
            </button>
            <button className="button button-primary" onClick={runAnalysis} disabled={running}>
              {running ? <LoaderCircle className="spin" size={16} /> : <Sparkles size={16} />}
              {running ? '分拣中' : '开始分拣'}
            </button>
            <input ref={fileRef} className="visually-hidden" type="file" accept=".json,application/json" onChange={onFileChange} />
            <input ref={archiveRef} className="visually-hidden" type="file" accept=".zip,application/zip" onChange={onArchiveChange} />
          </div>
        </header>

        <section className="page-heading">
          <div>
            <p className="eyebrow">ATTENTION QUEUE</p>
            <h1>{headline}</h1>
            <p className="heading-subtitle">
              {result ? formatNumber(result.summary.candidate_count) + ' 个候选事项 · ' + sourceLabel + ' · ' + dateRangeLabel : '正在读取消息归档…'}
            </p>
          </div>
        </section>

        {error && (
          <div className="error-banner">
            <AlertTriangle size={17} />
            <span>{error}</span>
            <button className="icon-button small" title="关闭" onClick={() => setError('')}><X size={15} /></button>
          </div>
        )}

        {archiveStale && result && (
          <div className="archive-banner">
            <AlertTriangle size={17} />
            <span>这份归档截至 <strong>{result.source.archive_end}</strong>，早于分析基准日 {result.source.as_of}。队列只代表已有记录，不代表之后没有新通知。</span>
          </div>
        )}

        {result && (
          <div className="scope-bar">
            <div className="scope-copy">
              <span className="scope-kicker"><CalendarRange size={15} /> 消息范围</span>
              <strong>{dateRangeLabel}</strong>
              <span>{hasDateFilter ? `首尾日期均包含 · 纳入 ${formatNumber(result.source.message_count)} / ${formatNumber(result.source.archive_message_count || result.source.message_count)} 条` : `完整归档 · ${formatNumber(result.source.message_count)} 条消息`}</span>
            </div>
            <div className="scope-controls">
              <div className="quick-range-group" role="group" aria-label="快捷日期范围">
                {QUICK_RANGE_OPTIONS.map((option) => (
                  <button key={option.id} className={activeQuickRangeId === option.id ? 'active' : ''} onClick={() => applyQuickRange(option.id)} disabled={running}>
                    {option.label}
                  </button>
                ))}
              </div>
              <button className="scope-settings-button" onClick={() => setSettingsOpen(true)}><SlidersHorizontal size={14} /> 范围设置</button>
            </div>
          </div>
        )}

        {loading ? (
          <div className="loading-grid">
            <div className="loading-panel shimmer" />
            <div className="loading-panel shimmer" />
          </div>
        ) : !result ? (
          <EmptyState onUpload={() => fileRef.current && fileRef.current.click()} />
        ) : (
          <>
            <section className="metric-strip">
              <div className="metric-item">
                <span className="metric-label">优先处理 · P0 / P1</span>
                <strong>{formatNumber(activeUrgentCount)}</strong>
                <span className="metric-note">件</span>
              </div>
              <div className="metric-item deadline-metric">
                <span className="metric-label">今日 / 临近</span>
                <strong>{formatNumber((deadlineStatusCounts.due_today || 0) + (deadlineStatusCounts.near || 0))}</strong>
                <span className="metric-note">未来 0–3 天</span>
              </div>
              <button type="button" className="metric-item archive-metric" onClick={() => selectQueueView('archive')}>
                <span className="metric-label">已归档</span>
                <strong>{formatNumber(archivedTotal)}</strong>
                <span className="metric-note">完成 {formatNumber(completedTotal)} / 超期 {formatNumber(overdueTotal)}</span>
              </button>
            </section>

            {hasMessages ? <div className="workspace-grid">
              <section className={'queue-panel ' + (queueView === 'archive' ? 'is-archive' : '')}>
                <div className="queue-view-tabs" role="tablist" aria-label="事项状态分区">
                  <button role="tab" aria-selected={queueView === 'active'} className={queueView === 'active' ? 'active' : ''} onClick={() => selectQueueView('active')}>
                    <span className="view-index">01</span>
                    <span><strong>进行中</strong><small>当前行动队列</small></span>
                    <b>{formatNumber(activeTotal)}</b>
                  </button>
                  <button role="tab" aria-selected={queueView === 'archive'} className={queueView === 'archive' ? 'active archive' : 'archive'} onClick={() => selectQueueView('archive')}>
                    <span className="view-index">02</span>
                    <span><strong>已归档</strong><small>完成 / 超期</small></span>
                    <b>{formatNumber(archivedTotal)}</b>
                  </button>
                </div>
                <div className="panel-head">
                  <div>
                    <h2>{queueView === 'archive' ? '归档事项' : '注意力队列'}</h2>
                    <span>{queueLimitLabel}{categoryFilter !== 'all' ? ' · ' + categoryLabel(categoryFilter) : ''}{reviewOnly ? ' · 仅看待复核' : ''}{query ? ' · 搜索结果' : ''}</span>
                  </div>
                  <div className="panel-head-actions">
                    <div className="search-box">
                      <Search size={15} />
                      <input placeholder="搜索当前队列" aria-label="搜索当前队列" value={query} onChange={(event) => setQuery(event.target.value)} />
                      {query && <button className="search-clear" title="清空搜索" onClick={() => setQuery('')}><X size={13} /></button>}
                    </div>
                    <select className="queue-category-select" aria-label="按类别筛选" title="按类别筛选" value={categoryFilter} onChange={(event) => setCategoryFilter(event.target.value)}>
                      <option value="all">全部类别</option>
                      {categoryOptions.map((value) => <option key={value} value={value}>{categoryLabel(value)}</option>)}
                    </select>
                    <button className={'queue-filter-button ' + (reviewOnly ? 'active' : '')} title="仅看待复核事项" aria-pressed={reviewOnly} onClick={() => setReviewOnly((value) => !value)}>
                      <Filter size={14} /> <span>复核</span><b>{formatNumber(viewReviewCount)}</b>
                    </button>
                  </div>
                </div>
                {queueView === 'active' && <div className="priority-tabs">
                  {['all', 'P0', 'P1', 'P2', 'P3'].map((tab) => (
                    <button key={tab} className={filter === tab ? 'active' : ''} onClick={() => setFilter(tab)}>
                      {tab === 'all' ? '全部' : tab}
                      <span>{formatNumber(getFilterCount(deadlineScopedItems, tab))}</span>
                    </button>
                  ))}
                </div>}
                {queueView === 'active' ? (
                  <div className="deadline-tabs" role="group" aria-label="按截止状态筛选">
                    {ACTIVE_DEADLINE_FILTERS.map((option) => (
                      <button key={option.id} className={deadlineFilter === option.id ? 'active' : ''} onClick={() => { setDeadlineFilter(option.id); setFilter('all'); }}>
                        {option.label}
                        <span>{formatNumber(option.id === 'all' ? activeTotal : activeItems.filter((item) => matchesDeadlineFilter(item, option.id)).length)}</span>
                      </button>
                    ))}
                  </div>
                ) : (
                  <div className="archive-filter-tabs" role="group" aria-label="按归档原因筛选">
                    {ARCHIVE_FILTERS.map((option) => (
                      <button key={option.id} className={archiveFilter === option.id ? 'active' : ''} onClick={() => setArchiveFilter(option.id)}>
                        {option.label}
                        <span>{formatNumber(option.id === 'all' ? archivedTotal : option.id === 'completed' ? completedTotal : overdueTotal)}</span>
                      </button>
                    ))}
                  </div>
                )}
                <div className="queue-list">
                  {displayedItems.map((item) => {
                    const priority = (item.decision && item.decision.priority) || 'P3';
                    const isSelected = item.candidate_id === (selectedItem && selectedItem.candidate_id);
                    return (
                      <div key={item.item_key || item.candidate_id} className={'queue-row ' + (isSelected ? 'selected' : '')}>
                        <span className={'priority-bar ' + (queueView === 'archive' ? 'archived' : ((PRIORITY_META[priority] && PRIORITY_META[priority].className) || 'p3'))} />
                        <button type="button" className="queue-select" onClick={() => setSelectedId(item.candidate_id)}>
                          <div className="queue-main">
                            <div className="queue-row-top">
                              {queueView === 'archive' ? <ArchivePill item={item} /> : <PriorityPill priority={priority} />}
                              <DeadlinePill item={item} archived={queueView === 'archive'} />
                              <span className="category-label">{categoryLabel(item.judgments && item.judgments.category && item.judgments.category.value)}</span>
                              {item.decision && item.decision.needs_review && <span className={'review-mark ' + (queueView === 'archive' ? 'archived' : '')}><AlertTriangle size={12} /> {queueView === 'archive' ? '历史复核' : '复核'}</span>}
                              {item.judgments && item.judgments.importance && item.judgments.importance.profile && (() => {
                                const pf = item.judgments.importance.profile;
                                const promoted = !!(item.decision && item.decision.profile_promoted);
                                const sunk = !!(item.decision && item.decision.profile_sunk);
                                const tone = promoted ? ' promoted' : (sunk ? ' sunk' : (pf.floor_applied ? ' rescued' : ''));
                                const negs = pf.negative_hits || [];
                                let tip;
                                if (promoted) tip = `画像判定为强相关，已提升优先级 · ${item.decision.reason || ''}`;
                                else if (sunk) tip = `画像判定为面向其他群体，已降级观察 · ${item.decision.reason || ''}`;
                                else if (negs.length) tip = `本地命中反向信号：${negs.join('、')}`;
                                else if (pf.local_hits && pf.local_hits.length) tip = `本地命中画像关键词：${pf.local_hits.join('、')}`;
                                else tip = `画像相关度 ${pf.relevance}/100`;
                                return (
                                  <span className={'profile-mark' + tone} title={tip}>
                                    <UserRound size={12} /> {promoted ? '画像 ↑' : (sunk ? '画像 ↓' : '画像')}
                                  </span>
                                );
                              })()}
                            </div>
                            <strong>{itemTitle(item)}</strong>
                            <p>{item.preview}</p>
                            <div className="queue-meta">
                              <span><Clock3 size={12} /> {deadlineText(item)}</span>
                              <span><Database size={12} /> {(item.message_ids && item.message_ids.length) || 0} 条证据</span>
                              <span><Check size={12} /> {formatPercent(confidence(item))}</span>
                            </div>
                          </div>
                          <ArrowUpRight size={16} className="row-arrow" />
                        </button>
                        {(queueView === 'active' || archiveReason(item) === 'completed') && (
                          <button
                            type="button"
                            className={'queue-complete ' + (queueView === 'archive' ? 'undo' : '')}
                            title={queueView === 'archive' ? '撤销完成' : '完成并归档'}
                            aria-label={`${queueView === 'archive' ? '撤销完成' : '完成并归档'}：${itemTitle(item)}`}
                            disabled={completionPending.has(item.item_key)}
                            onClick={() => setItemCompleted(item, queueView === 'active')}
                          >
                            {queueView === 'archive' ? <RotateCcw size={16} /> : <Check size={17} />}
                          </button>
                        )}
                      </div>
                    );
                  })}
                  {displayedItems.length < filteredItems.length && (
                    <button className="load-more" onClick={() => setVisibleCount((value) => value + 160)}>
                      显示更多 <span>{formatNumber(displayedItems.length)} / {formatNumber(filteredItems.length)}</span>
                    </button>
                  )}
                  {!filteredItems.length && <div className="no-results">{queueView === 'archive' && archiveFilter === 'completed' && !completedTotal ? '尚无已完成事项。' : '当前分区没有匹配事项。'}</div>}
                </div>
              </section>

              <aside className="inspector-panel">
                {selectedItem ? (
                  <>
                    <div className="inspector-head">
                      <div>
                        <span className="inspector-kicker"><PanelRight size={14} /> 决策轨迹</span>
                        <h2>{itemTitle(selectedItem)}</h2>
                      </div>
                      <div className="inspector-statuses">
                        <DeadlinePill item={selectedItem} archived={queueView === 'archive'} />
                        {queueView === 'archive' ? <ArchivePill item={selectedItem} /> : <PriorityPill priority={(selectedItem.decision && selectedItem.decision.priority) || 'P3'} />}
                        {(queueView === 'active' || archiveReason(selectedItem) === 'completed') && (
                          <button className="inspector-completion" disabled={completionPending.has(selectedItem.item_key)} onClick={() => setItemCompleted(selectedItem, queueView === 'active')}>
                            {queueView === 'archive' ? <RotateCcw size={14} /> : <Check size={14} />}
                            {queueView === 'archive' ? '撤销完成' : '完成并归档'}
                          </button>
                        )}
                      </div>
                    </div>
                    <div className="inspector-copy">{selectedItem.preview}</div>
                    <div className="score-grid">
                      <div><span>重要性</span><strong>{(selectedItem.decision && selectedItem.decision.importance) || 0}</strong><small>/100</small></div>
                      <div><span>紧迫性</span><strong>{(selectedItem.decision && selectedItem.decision.urgency) || 0}</strong><small>/100</small></div>
                      <div><span>风险</span><strong>{(selectedItem.decision && selectedItem.decision.risk) || 0}</strong><small>/100</small></div>
                    </div>
                    {(() => {
                      const relevance = selectedItem.judgments && selectedItem.judgments.relevance;
                      const importanceProfile = selectedItem.judgments && selectedItem.judgments.importance && selectedItem.judgments.importance.profile;
                      if (!relevance && !importanceProfile) return null;
                      return (
                        <div className="profile-trace">
                          <div className="section-label"><UserRound size={13} /> 画像影响</div>
                          {relevance && (
                            <div className="profile-trace-line">
                              <span className="profile-trace-label">相关度</span>
                              <span className="profile-trace-value">{Math.round(relevance.value || 0)}<small>/100</small></span>
                              <span className="profile-trace-meta">
                                置信度 {formatPercent(relevance.confidence)}
                                {relevance.source === 'local-estimate'
                                  ? ' · 本地规则估算'
                                  : (relevance.reason && relevance.reason.indexOf('本地命中') === 0 ? ' · 本地硬信号兜底' : '')}
                              </span>
                            </div>
                          )}
                          {importanceProfile && (() => {
                            const w = Number(importanceProfile.weight || 1);
                            const dir = w > 1.001 ? 'boost' : (w < 0.999 ? 'damp' : 'flat');
                            return (
                              <div className="profile-trace-line">
                                <span className="profile-trace-label">重要性调整</span>
                                <span className="profile-trace-value">
                                  <em>{importanceProfile.base}</em>
                                  <ArrowRight size={13} />
                                  <strong>{Math.round((selectedItem.judgments.importance && selectedItem.judgments.importance.value) || 0)}</strong>
                                </span>
                                <span className={'profile-trace-meta w-' + dir}>
                                  权重 ×{w.toFixed(2)}
                                  {dir === 'boost' ? ' · 相关度高，加分' : (dir === 'damp' ? ' · 相关度低，降分' : '')}
                                  {importanceProfile.floor_applied ? ' · 命中画像关键词，保底未沉底' : ''}
                                </span>
                              </div>
                            );
                          })()}
                          {(importanceProfile && importanceProfile.local_hits && importanceProfile.local_hits.length > 0) && (
                            <div className="profile-hit-tags">
                              {importanceProfile.local_hits.map((hit) => <span key={hit} className="profile-hit-tag">{hit}</span>)}
                            </div>
                          )}
                          {(importanceProfile && importanceProfile.negative_hits && importanceProfile.negative_hits.length > 0) && (
                            <div className="profile-hit-tags">
                              {importanceProfile.negative_hits.map((hit) => <span key={hit} className="profile-hit-tag neg">{hit}</span>)}
                            </div>
                          )}
                          {selectedItem.decision && selectedItem.decision.profile_promoted && (
                            <div className="profile-trace-line">
                              <span className="profile-trace-label">优先级提升</span>
                              <span className="profile-trace-value">
                                <em>{selectedItem.decision.profile_priority_before || '原级'}</em>
                                <ArrowRight size={13} />
                                <strong>{selectedItem.decision.priority}</strong>
                              </span>
                              <span className="profile-trace-meta">画像强相关，已提一级 · 上限不超过 P1</span>
                            </div>
                          )}
                          {selectedItem.decision && selectedItem.decision.profile_sunk && (
                            <div className="profile-trace-line">
                              <span className="profile-trace-label">优先级降级</span>
                              <span className="profile-trace-value">
                                <em>{selectedItem.decision.profile_priority_before || '原级'}</em>
                                <ArrowRight size={13} />
                                <strong>{selectedItem.decision.priority}</strong>
                              </span>
                              <span className="profile-trace-meta w-damp">画像判定面向其他群体，降级观察</span>
                            </div>
                          )}
                        </div>
                      );
                    })()}
                    <div className="trace-section">
                      <div className="section-label">JEV typed judgments</div>
                      <DecisionRow label="是否公告" type="noul" value={selectedItem.judgments && selectedItem.judgments.is_announcement && selectedItem.judgments.is_announcement.value ? '是' : '否'} confidence={selectedItem.judgments && selectedItem.judgments.is_announcement && selectedItem.judgments.is_announcement.confidence} tone="coral" />
                      <DecisionRow label="记录类型" type="choice" value={recordKindLabel(selectedItem.judgments && selectedItem.judgments.record_kind && selectedItem.judgments.record_kind.value)} confidence={selectedItem.judgments && selectedItem.judgments.record_kind && selectedItem.judgments.record_kind.confidence} tone="green" />
                      <DecisionRow label="事项类别" type="choice" value={categoryLabel(selectedItem.judgments && selectedItem.judgments.category && selectedItem.judgments.category.value)} confidence={selectedItem.judgments && selectedItem.judgments.category && selectedItem.judgments.category.confidence} tone="blue" />
                      <DecisionRow label={queueView === 'archive' ? '原始行动判断' : '是否需行动'} type="noul" value={selectedItem.judgments && selectedItem.judgments.action_required && selectedItem.judgments.action_required.value ? '需要' : '无需'} confidence={selectedItem.judgments && selectedItem.judgments.action_required && selectedItem.judgments.action_required.confidence} tone="amber" />
                      <DecisionRow label="受众" type="choice" value={(selectedItem.judgments && selectedItem.judgments.audience && selectedItem.judgments.audience.value) || '未知'} confidence={selectedItem.judgments && selectedItem.judgments.audience && selectedItem.judgments.audience.confidence} tone="green" />
                      <DecisionRow label="截止状态" type="state" value={deadlineRelativeText(selectedItem)} confidence={1} tone={deadlineStatus(selectedItem) === 'overdue' ? 'coral' : 'amber'} />
                      <DecisionRow label="DDL parser" type="app" value={deadlineText(selectedItem)} confidence={selectedItem.judgments && selectedItem.judgments.deadline && selectedItem.judgments.deadline.confidence} />
                    </div>
                    <div className="reducer-section">
                      <div className="section-label">Reducer 输出</div>
                      <div className="reducer-line">
                        <span className={'reducer-dot ' + (queueView === 'archive' ? 'archived' : '')} />
                        <div>
                          <strong>{queueView === 'archive' ? (archiveReason(selectedItem) === 'completed' ? '已标记完成' : '已自动归档') : PRIORITY_META[(selectedItem.decision && selectedItem.decision.priority) || 'P3'].label}</strong>
                          <p>{queueView === 'archive' ? `${archiveReason(selectedItem) === 'completed' ? '手动完成' : '截止日已过'} · 原判断 ${(selectedItem.decision && selectedItem.decision.priority) || 'P3'} · ${selectedItem.decision && selectedItem.decision.reason}` : selectedItem.decision && selectedItem.decision.reason}</p>
                        </div>
                      </div>
                      {queueView === 'active' && selectedItem.decision && selectedItem.decision.needs_review && (
                        <div className="manual-review">
                          <AlertTriangle size={15} />
                          <span>保留人工复核</span>
                          <button className="toggle on" aria-label="保留人工复核"><span /></button>
                        </div>
                      )}
                    </div>
                    <div className="evidence-section">
                      <div className="section-label">证据消息</div>
                      {((selectedItem.evidence) || []).slice(0, 3).map((evidence) => (
                        <div key={evidence.id} className="evidence-item">
                          <span>{evidence.id}</span>
                          <p>{evidence.content}</p>
                          <time>{evidence.timestamp}</time>
                        </div>
                      ))}
                      {(!selectedItem.evidence || !selectedItem.evidence.length) && <p className="muted-note">缓存结果保留了证据 ID，上传或重新分拣后可展开原文。</p>}
                    </div>
                  </>
                ) : (
                  <div className="inspector-empty"><PanelRight size={28} /><span>选择一条事项查看判断轨迹</span></div>
                )}
              </aside>
            </div> : (
              <RangeEmptyState
                dateRangeLabel={dateRangeLabel}
                archiveCount={result.source.archive_message_count || 0}
                onReset={() => applyQuickRange('all')}
                onOpenSettings={() => setSettingsOpen(true)}
              />
            )}
          </>
        )}
        </>}
      </main>

      {settingsOpen && (
        <div className="drawer-backdrop" onClick={() => setSettingsOpen(false)}>
          <aside className="settings-drawer" onClick={(event) => event.stopPropagation()}>
            <div className="drawer-head">
              <div>
                <span className="inspector-kicker"><Settings2 size={14} /> 工作台设置</span>
                <h2>连接与范围</h2>
              </div>
              <button className="icon-button" title="关闭" onClick={() => setSettingsOpen(false)}><X size={18} /></button>
            </div>
            <div className="form-section">
              <label className="field-label">数据源</label>
              <div className="source-switcher">
                <button className={sourceMode === 'file' ? 'source-option selected' : 'source-option'} onClick={() => setSourceMode('file')}>
                  <FileJson size={18} />
                  <span>导入文件</span>
                  <small>messages.json</small>
                </button>
                <button className={sourceMode === 'archive' ? 'source-option selected' : 'source-option'} onClick={() => setSourceMode('archive')}>
                  <Archive size={18} />
                  <span>聊天记录包</span>
                  <small>微信导出 zip</small>
                </button>
                <button className={sourceMode === 'wechat' ? 'source-option selected' : 'source-option'} onClick={() => setSourceMode('wechat')}>
                  <MessageCircle size={18} />
                  <span>本机微信</span>
                  <small>导出器优先</small>
                </button>
                <button className={sourceMode === 'inbox' ? 'source-option selected' : 'source-option'} onClick={() => setSourceMode('inbox')}>
                  <Inbox size={18} />
                  <span>转发收件箱</span>
                  <small>微信转发自动收</small>
                </button>
              </div>
              {sourceMode === 'file' ? (
                <button className="upload-drop" onClick={() => fileRef.current && fileRef.current.click()}>
                  <FileJson size={22} />
                  <span>{file ? file.name : '选择 messages.json'}</span>
                  <small>{file ? Math.round(file.size / 1024) + ' KB' : '支持微信导出的对象格式或消息数组'}</small>
                </button>
              ) : sourceMode === 'archive' ? (
                <div className="archive-source-panel">
                  <button className="upload-drop" onClick={() => archiveRef.current && archiveRef.current.click()}>
                    <Archive size={22} />
                    <span>{archiveFile ? archiveFile.name : '选择聊天记录压缩包'}</span>
                    <small>{archiveFile ? Math.round(archiveFile.size / 1024) + ' KB' : '微信「导出聊天记录」得到的 zip'}</small>
                  </button>
                  <div className="selected-group-note">
                    <ShieldCheck size={15} /> 本地解析 zip 内的聊天记录文本与附件，只读、不上传原始聊天内容；解出的消息包保存在被 Git 忽略的本地目录。
                  </div>
                  {archiveFile && (
                    <button className="button button-quiet archive-clear" onClick={() => setArchiveFile(null)}>
                      <X size={14} /> 移除已选压缩包
                    </button>
                  )}
                </div>
              ) : sourceMode === 'inbox' ? (
                <div className="archive-source-panel inbox-source-panel">
                  <div className="wechat-status-line">
                    <span className={'status-dot ' + (inboxStatus && inboxStatus.watch_dir_exists ? 'local' : 'remote')} />
                    <span>{inboxStatus ? (inboxStatus.watch_dir_exists ? '正在监视 WorkBuddy 微信转发（每 ' + (inboxStatus.poll_seconds || 0) + ' 秒自动收取）' : '未找到 WorkBuddy 分享目录，转发一条聊天记录试试') : '正在检测转发收件箱…'}</span>
                    <button className="icon-button small" title="立即刷新" onClick={() => loadInbox()}>
                      {inboxLoading ? <LoaderCircle size={13} className="spin" /> : <RefreshCw size={13} />}
                    </button>
                  </div>
                  <div className="wechat-group-list">
                    {inboxLoading && !inboxItems.length && <div className="wechat-list-note"><LoaderCircle className="spin" size={15} /> 正在读取收件箱</div>}
                    {!inboxLoading && !inboxItems.length && <div className="wechat-list-note">收件箱还是空的——在微信里把聊天记录「导出聊天记录」后转发给 WorkBuddy，几秒后会自动出现在这里</div>}
                    {inboxItems.slice().reverse().map((item) => (
                      <button
                        key={item.id}
                        className={'wechat-group-row ' + (selectedInbox && selectedInbox.filename === item.filename ? 'selected' : '')}
                        onClick={() => setSelectedInbox(item)}
                      >
                        <span className="group-avatar"><Inbox size={15} /></span>
                        <span className="wechat-group-copy">
                          <strong>{item.filename}</strong>
                          <small>
                            {(item.received_at || '').replace('T', ' ')} · {Math.round((item.size || 0) / 1024)} KB ·{' '}
                            {item.status === 'imported' ? '已导入' : item.status === 'error' ? '导入失败' : '新到'}
                          </small>
                        </span>
                        {selectedInbox && selectedInbox.filename === item.filename && <Check size={15} />}
                      </button>
                    ))}
                  </div>
                  {selectedInbox && (
                    <div className="selected-group-note">
                      <ShieldCheck size={15} /> 已选择 <strong>{selectedInbox.filename}</strong>
                      {selectedInbox.status === 'imported' ? '（此前已导入过，可重新分拣）' : ''}
                      ，分拣只在本机完成，消息包保存到被 Git 忽略的本地目录。
                    </div>
                  )}
                  {selectedInbox && selectedInbox.status === 'error' && (
                    <div className="selected-group-note error-note">{selectedInbox.error || '上次导入失败'}</div>
                  )}
                </div>
              ) : (
                <div className="wechat-source-panel">
                  <div className="wechat-status-line">
                    <span className={'status-dot ' + (wechatStatus && wechatStatus.ready ? 'local' : 'remote')} />
                    <span>{wechatStatus ? (wechatStatus.ready ? (wechatStatus.exporter && wechatStatus.exporter.ready ? '微信导出器 / 本地归档可读' : '本地微信归档可读') : '微信数据源未就绪') : '正在检测微信数据源…'}</span>
                    {wechatStatus && <small>{wechatStatus.message_db_count || 0} 个消息分片</small>}
                  </div>
                  <div className="search-box group-search">
                    <Search size={15} />
                    <input placeholder="搜索群名或 username" aria-label="搜索微信群" value={wechatQuery} onChange={(event) => setWechatQuery(event.target.value)} />
                  </div>
                  <div className="wechat-group-list">
                    {wechatLoading && <div className="wechat-list-note"><LoaderCircle className="spin" size={15} /> 正在读取群列表</div>}
                    {!wechatLoading && wechatGroups.map((group) => (
                      <button
                        key={group.username}
                        className={'wechat-group-row ' + (selectedGroup && selectedGroup.username === group.username ? 'selected' : '')}
                        onClick={() => setSelectedGroup(group)}
                      >
                        <span className="group-avatar"><Users size={15} /></span>
                        <span className="wechat-group-copy">
                          <strong>{group.display_name}</strong>
                          <small>{group.message_count == null ? (group.has_messages ? '有消息' : '暂无消息') : formatNumber(group.message_count) + ' 条消息'} · {group.latest_date || '导出后显示日期'}</small>
                        </span>
                        {selectedGroup && selectedGroup.username === group.username && <Check size={15} />}
                      </button>
                    ))}
                    {!wechatLoading && !wechatGroups.length && <div className="wechat-list-note">没有匹配的微信群</div>}
                  </div>
                  {selectedGroup && (
                    <>
                      <div className="selected-group-note">
                        <ShieldCheck size={15} /> 已选择 <strong>{selectedGroup.display_name}</strong>，读取后会保存本地消息包并进入同一套分拣流程。
                      </div>
                      <label className="check-line">
                        <input type="checkbox" checked={resolveWechatFiles} onChange={(event) => setResolveWechatFiles(event.target.checked)} />
                        <span>尝试定位已经下载到本机的文件</span>
                      </label>
                    </>
                  )}
                </div>
              )}
            </div>
            <div className="form-section">
              <div className="field-label">
                <span>消息日期范围</span>
                <button
                  className="icon-button small"
                  title="清空日期范围"
                  onClick={() => { setFromDate(''); setToDate(''); }}
                  disabled={!fromDate && !toDate}
                >
                  <X size={13} />
                </button>
              </div>
              <div className="date-range-fields">
                <div>
                  <label className="sub-field-label" htmlFor="from-date">开始日期</label>
                  <input id="from-date" className="text-input" type="date" value={fromDate} max={toDate || undefined} onInput={(event) => setFromDate(event.target.value)} onChange={(event) => setFromDate(event.target.value)} />
                </div>
                <div>
                  <label className="sub-field-label" htmlFor="to-date">结束日期</label>
                  <input id="to-date" className="text-input" type="date" value={toDate} min={fromDate || undefined} onInput={(event) => setToDate(event.target.value)} onChange={(event) => setToDate(event.target.value)} />
                </div>
              </div>
              <div className="quick-range-row" role="group" aria-label="快捷日期范围">
                {QUICK_RANGE_OPTIONS.map((option) => (
                  <button key={option.id} className={activeQuickRangeId === option.id ? 'active' : ''} onClick={() => applyQuickRange(option.id)} disabled={running}>
                    {option.label}
                  </button>
                ))}
              </div>
              <div className="draft-range-note">当前设置：<strong>{draftDateRangeLabel}</strong></div>
              <p className="range-help">可只填一端；筛选先于候选聚类。分析基准日仍用于判断 DDL 是否临近或逾期。</p>
            </div>
            <p className="privacy-note">{sourceMode === 'wechat' ? '本机微信入口只读导出 JSON 或本地解密 SQLite；只有选择远程判断时，候选片段才会发送到你填写的 API。' : provider === 'local' ? '本地模式不会上传聊天内容，全部判断在本机完成。' : '远程模式会把候选消息片段发送到你选择的 API；本地模式不会上传聊天内容。'}</p>
            <div className="form-section">
              <label className="field-label">判断提供方</label>
              <div className="provider-options">
                <button className={provider === 'local' ? 'selected' : ''} onClick={() => setProvider('local')}>
                  <span><Database size={16} /> 本地 Jev 基线</span><small>规则 + typed reducer，不上传</small>
                </button>
                <button className={provider === 'jev' ? 'selected' : ''} onClick={() => selectProvider('jev')}>
                  <span><Sparkles size={16} /> TypeSafe Jev</span><small>官方 System One endpoint</small>
                </button>
                <button className={provider === 'deepseek' ? 'selected' : ''} onClick={() => selectProvider('deepseek')}>
                  <span><Brain size={16} /> DeepSeek</span><small>OpenAI 兼容接口</small>
                </button>
                <button className={provider === 'custom' ? 'selected' : ''} onClick={() => selectProvider('custom')}>
                  <span><SlidersHorizontal size={16} /> 自定义 Jev API</span><small>兼容 state + questions</small>
                </button>
              </div>
            </div>
            {provider !== 'local' && (
              <>
                <div className="form-section">
                  <label className="field-label" htmlFor="endpoint">Endpoint</label>
                  <input id="endpoint" className="text-input" value={endpoint} onChange={(event) => setEndpoint(event.target.value)} />
                  {provider === 'deepseek' && <p className="range-help">填 base_url（如 https://api.deepseek.com）会自动补 /chat/completions。缺省用官方地址。</p>}
                </div>
                <div className="form-section inline-fields">
                  <div>
                    <label className="field-label" htmlFor="model">模型标识</label>
                    {provider === 'deepseek' ? (
                      <select id="model" className="text-input" value={model} onChange={(event) => setModel(event.target.value)}>
                        <option value="deepseek-flash">deepseek-flash</option>
                        <option value="deepseek-v4-pro">deepseek-v4-pro</option>
                      </select>
                    ) : (
                      <input id="model" className="text-input" value={model} onChange={(event) => setModel(event.target.value)} />
                    )}
                  </div>
                  <div>
                    <label className="field-label" htmlFor="max-candidates">API 调用上限</label>
                    <select id="max-candidates" className="text-input" value={maxCandidates} onChange={(event) => setMaxCandidates(event.target.value)}>
                      <option value="24">24 条</option>
                      <option value="48">48 条</option>
                      <option value="96">96 条</option>
                      <option value="160">160 条</option>
                    </select>
                  </div>
                </div>
                <div className="form-section">
                  <label className="field-label" htmlFor="api-key">API key <span>只在本次请求使用</span></label>
                  <input id="api-key" className="text-input" type="password" placeholder="粘贴到这里，不会写入文件" value={apiKey} onChange={(event) => setApiKey(event.target.value)} />
                </div>
              </>
            )}
            <div className="drawer-footer">
              <button className="button button-quiet" onClick={() => setSettingsOpen(false)}>取消</button>
              <button className="button button-primary" onClick={runAnalysis} disabled={running}>
                {running ? <LoaderCircle className="spin" size={16} /> : <Send size={16} />}
                {running ? '运行中' : '应用并分拣'}
              </button>
            </div>
          </aside>
        </div>
      )}

      {profileOpen && (
        <div className="drawer-backdrop" onClick={() => setProfileOpen(false)}>
          <aside className="settings-drawer profile-drawer" onClick={(event) => event.stopPropagation()}>
            <div className="drawer-head">
              <div>
                <span className="inspector-kicker"><UserRound size={14} /> 我的画像</span>
                <h2>让分拣知道你关心什么</h2>
              </div>
              <button className="icon-button" title="关闭" onClick={() => setProfileOpen(false)}><X size={18} /></button>
            </div>

            <div className="form-section">
              <label className="toggle-line">
                <span>
                  <strong>启用画像增强</strong>
                  <small>开启后，判断时多问一个「跟我有没有关系」，据此温和调整重要性</small>
                </span>
                <button
                  className={'toggle ' + (profile.enabled ? 'on' : '')}
                  aria-label="启用画像增强"
                  onClick={() => patchProfile({ enabled: !profile.enabled })}
                ><span /></button>
              </label>
              {!profile.enabled && (
                <div className="selected-group-note">
                  <ShieldCheck size={15} /> 默认关闭。关闭时判断流程与没有画像时完全一致，不会发送任何额外信息。
                </div>
              )}
            </div>

            <div className="form-section">
              <label className="field-label" htmlFor="profile-college">学院</label>
              <select
                id="profile-college"
                className="text-input"
                value={profile.college || ''}
                onChange={(event) => patchProfile({ college: event.target.value })}
              >
                <option value="">未填写</option>
                {profileMeta.colleges.map((college) => (
                  <option key={college} value={college}>{college}</option>
                ))}
              </select>
            </div>

            <div className="form-section inline-fields">
              <div>
                <label className="field-label" htmlFor="profile-major">专业</label>
                <input
                  id="profile-major"
                  className="text-input"
                  placeholder="如：计算机科学与技术"
                  value={profile.major || ''}
                  maxLength={profileMeta.limits && profileMeta.limits.major ? profileMeta.limits.major : 40}
                  onChange={(event) => patchProfile({ major: event.target.value })}
                />
              </div>
              <div>
                <label className="field-label" htmlFor="profile-grade">年级</label>
                <select
                  id="profile-grade"
                  className="text-input"
                  value={profile.grade || ''}
                  onChange={(event) => patchProfile({ grade: event.target.value })}
                >
                  <option value="">未填写</option>
                  {Array.from(
                    { length: (profileMeta.grade_range[1] - profileMeta.grade_range[0]) + 1 },
                    (_, index) => String(profileMeta.grade_range[1] - index)
                  ).map((year) => (
                    <option key={year} value={year}>{year} 级</option>
                  ))}
                </select>
              </div>
            </div>

            <div className="form-section">
              <label className="field-label" htmlFor="profile-interests">关注方向</label>
              <div className="interest-editor">
                <div className="interest-tags">
                  {(profile.interests || []).map((interest) => (
                    <span key={interest} className="interest-tag">
                      {interest}
                      <button className="interest-remove" title="移除" onClick={() => removeInterest(interest)}><X size={12} /></button>
                    </span>
                  ))}
                  {!(profile.interests || []).length && <span className="muted-note">还没有标签，比如「竞赛」「实习」「考研」</span>}
                </div>
                <div className="interest-add">
                  <input
                    id="profile-interests"
                    className="text-input"
                    placeholder="输入后回车添加"
                    value={interestDraft}
                    onChange={(event) => setInterestDraft(event.target.value)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') {
                        event.preventDefault();
                        addInterest();
                      }
                    }}
                  />
                  <button className="button button-quiet" onClick={addInterest} disabled={!interestDraft.trim()}>添加</button>
                </div>
              </div>
            </div>

            <div className="form-section">
              <label className="field-label" htmlFor="profile-notes">补充描述 <span>自由文本，最多 {profileMeta.limits && profileMeta.limits.notes ? profileMeta.limits.notes : 300} 字</span></label>
              <textarea
                id="profile-notes"
                className="text-input profile-notes"
                rows={4}
                placeholder="例如：正在准备考研，关注推免和奖学金；不住校，宿管通知可忽略。"
                value={profile.notes || ''}
                maxLength={profileMeta.limits && profileMeta.limits.notes ? profileMeta.limits.notes : 300}
                onChange={(event) => patchProfile({ notes: event.target.value })}
              />
              <p className="range-help">这段描述会作为背景资料发给远程模型，并明确标注为「非指令」。只写与判断相关性有关的信息。</p>
            </div>

            <p className="privacy-note">画像只保存在本机 <code>data/attention-desk/profile.json</code>（已被 Git 忽略），不会自动上传。选择远程判断时，画像与候选消息片段一并发送，用于回答「相关性」这一项。</p>

            <div className="drawer-footer">
              {profileNotice && <span className="drawer-notice">{profileNotice}</span>}
              <button className="button button-quiet" onClick={() => setProfileOpen(false)}>关闭</button>
              <button className="button button-primary" onClick={() => saveProfile()} disabled={profileBusy}>
                {profileBusy ? <LoaderCircle className="spin" size={16} /> : <Check size={16} />}
                {profileBusy ? '保存中' : '保存画像'}
              </button>
            </div>
          </aside>
        </div>
      )}

      {mailOpen && (
        <div className="drawer-backdrop" onClick={() => setMailOpen(false)}>
          <aside className="settings-drawer mail-drawer" onClick={(event) => event.stopPropagation()}>
            <div className="drawer-head">
              <div>
                <span className="inspector-kicker"><Mail size={14} /> 邮件摘要</span>
                <h2>发送 DDL 待办清单</h2>
              </div>
              <button className="icon-button" title="关闭" onClick={() => setMailOpen(false)}><X size={18} /></button>
            </div>

            <div className="mail-status-line">
              <span className={'status-dot ' + (mailStatus && mailStatus.ready ? 'local' : 'remote')} />
              <span>
                {mailStatus
                  ? (mailStatus.ready
                    ? <>发件邮箱 <strong>{mailStatus.sender}</strong>{mailStatus.daily_send_quota ? ` · 今日配额 ${mailStatus.daily_send_quota} 封` : ''}</>
                    : (mailStatus.reason || '邮件链路未就绪'))
                  : '正在检测邮件链路…'}
              </span>
            </div>

            <div className="form-section">
              <label className="field-label">正文格式</label>
              <div className="mail-format-switch" role="group" aria-label="邮件正文格式">
                <button
                  className={mailFormat === 'html' ? 'active' : ''}
                  onClick={() => changeMailFormat('html')}
                >
                  <LayoutPanelTop size={15} />
                  <span>卡片式富文本</span>
                  <small>HTML · 推荐</small>
                </button>
                <button
                  className={mailFormat === 'text' ? 'active' : ''}
                  onClick={() => changeMailFormat('text')}
                >
                  <FileText size={15} />
                  <span>纯文本</span>
                  <small>兼容性最好</small>
                </button>
              </div>
            </div>

            <div className="form-section">
              <label className="field-label" htmlFor="mail-recipients">收件人 <span>支持多个，逗号分隔</span></label>
              <textarea
                id="mail-recipients"
                className="text-input mail-recipients"
                rows={2}
                placeholder="a@example.com, b@example.com"
                value={mailRecipients}
                onChange={(event) => { setMailRecipients(event.target.value); setMailPreview(null); }}
              />
              <p className="range-help">地址只保存在本机浏览器；每次修改后需要重新生成预览。</p>
            </div>

            <div className="mail-actions">
              <button className="button button-quiet" onClick={() => prepareMail()} disabled={mailBusy || !result}>
                {mailBusy && !mailPreview ? <LoaderCircle className="spin" size={15} /> : <RefreshCw size={15} />}
                生成 / 刷新预览
              </button>
            </div>

            {mailNotice && (
              <div className={'mail-notice ' + (mailSent ? 'ok' : 'warn')}>
                {mailSent ? <Check size={15} /> : <AlertTriangle size={15} />}
                <span>{mailNotice}{mailSent && mailSent.recipients ? ` 收件人：${mailSent.recipients.join('、')}` : ''}</span>
              </div>
            )}

            {mailPreview && (
              <>
                <div className="form-section">
                  <label className="field-label">
                    主题
                    <span className="mail-format-tag">
                      {mailPreview.body_format === 'html' ? '卡片式富文本' : '纯文本'}
                    </span>
                  </label>
                  <div className="mail-subject">{mailPreview.subject}</div>
                  <label className="field-label mail-body-label">正文预览</label>
                  {mailPreview.body_format === 'html' ? (
                    <iframe
                      className="mail-body-frame"
                      title="邮件正文预览"
                      sandbox=""
                      srcDoc={mailPreview.body}
                    />
                  ) : (
                    <pre className="mail-body-preview">{mailPreview.body}</pre>
                  )}
                </div>
                <div className="mail-confirm-bar">
                  <span className="mail-confirm-note"><ShieldCheck size={14} /> 点确认才会真正发出</span>
                  <button className="button button-primary" onClick={confirmMailSend} disabled={mailBusy}>
                    {mailBusy ? <LoaderCircle className="spin" size={16} /> : <Send size={16} />}
                    {mailBusy ? '发送中' : '确认发送'}
                  </button>
                </div>
              </>
            )}

            {!mailPreview && !mailNotice && (
              <p className="privacy-note mail-hint">邮件只包含截止时间与摘要，不含聊天原文；生成预览后可以再确认一次才发送。</p>
            )}
          </aside>
        </div>
      )}
    </div>
  );
}

export default App;
