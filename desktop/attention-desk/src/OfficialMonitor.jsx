import { useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  ArrowUpRight,
  CalendarClock,
  Check,
  CheckCheck,
  CheckCircle2,
  ExternalLink,
  FileSearch,
  GripVertical,
  Globe2,
  LoaderCircle,
  Pin,
  PinOff,
  RefreshCw,
  RotateCcw,
  Search,
  Tag,
  X,
} from 'lucide-react';
import './official.css';

const STATUS = {
  not_checked: { label: '待检查', className: 'idle' },
  ok: { label: '正常', className: 'ok' },
  empty: { label: '暂无公告', className: 'idle' },
  auth_required: { label: '需认证', className: 'warn' },
  partial: { label: '部分可用', className: 'warn' },
  error: { label: '检查失败', className: 'error' },
};

const CATEGORY_META = {
  competition_activity: { label: '比赛 / 活动', className: 'competition' },
  publicity: { label: '公示', className: 'publicity' },
  other: { label: '其他公告', className: 'other' },
  unknown: { label: '待确认', className: 'unknown' },
};

const RANGES = [
  { id: '7', label: '近 7 天' },
  { id: '30', label: '近 30 天' },
  { id: 'all', label: '全部' },
];

const OFFICIAL_SOURCE_LAYOUT_KEY = 'attention-official-source-layout';

function readOfficialSourceLayout() {
  if (typeof window === 'undefined') return { order: [], pinned: [] };
  try {
    const raw = window.localStorage.getItem(OFFICIAL_SOURCE_LAYOUT_KEY);
    if (!raw) return { order: [], pinned: [] };
    const parsed = JSON.parse(raw);
    const clean = (value) => Array.isArray(value)
      ? value.filter((id) => typeof id === 'string' && id.trim())
      : [];
    return { order: clean(parsed?.order), pinned: clean(parsed?.pinned) };
  } catch {
    return { order: [], pinned: [] };
  }
}

function saveOfficialSourceLayout(layout) {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(OFFICIAL_SOURCE_LAYOUT_KEY, JSON.stringify(layout));
  } catch {
    // Private browsing or storage policy can make localStorage unavailable.
  }
}

function normalizeOfficialSourceLayout(layout, sourceIds) {
  const ids = [...new Set(sourceIds)];
  const available = new Set(ids);
  const order = [];
  const pinned = [];
  (Array.isArray(layout?.order) ? layout.order : []).forEach((id) => {
    if (available.has(id) && !order.includes(id)) order.push(id);
  });
  ids.forEach((id) => {
    if (!order.includes(id)) order.push(id);
  });
  (Array.isArray(layout?.pinned) ? layout.pinned : []).forEach((id) => {
    if (available.has(id) && !pinned.includes(id)) pinned.push(id);
  });
  return { order, pinned };
}

function sameOfficialSourceLayout(left, right) {
  return left.order.length === right.order.length
    && left.pinned.length === right.pinned.length
    && left.order.every((id, index) => id === right.order[index])
    && left.pinned.every((id, index) => id === right.pinned[index]);
}

function shortTime(value) {
  return value ? value.replace('T', ' ').slice(0, 16) : '未检查';
}

function dateFloor(days) {
  const day = new Date();
  day.setHours(0, 0, 0, 0);
  day.setDate(day.getDate() - days + 1);
  return `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`;
}

function postJson(path, payload) {
  return fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  }).then(async (response) => {
    const data = await response.json();
    if (!response.ok) throw new Error(data.detail || '操作失败');
    return data;
  });
}

function categoryMeta(value) {
  return CATEGORY_META[value] || CATEGORY_META.unknown;
}

function deadlineValue(notice) {
  return notice?.deadline?.value || '';
}

function deadlineLabel(notice) {
  const value = deadlineValue(notice);
  if (value) return value;
  return notice?.detail_fetched_at ? '未识别' : '待读取正文';
}

function formatDate(value) {
  if (!value) return '发布日期待核';
  return value.slice(0, 10);
}

function OfficialDetail({ notice, detail, loading, error, busy, onClose, onRetry, onRead, onAction }) {
  const category = categoryMeta(detail?.category || notice?.category);
  const completed = Boolean(detail?.completed ?? notice?.completed);
  const read = detail?.read ?? notice?.read;
  const sourceName = detail?.source_name || notice?.source_name;
  const summary = detail?.summary || detail?.detail_summary;
  return (
    <div className="official-detail-backdrop" onClick={onClose}>
      <aside
        className="official-detail-drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby="official-detail-title"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="official-detail-head">
          <div>
            <span className="official-detail-kicker"><FileSearch size={14} /> 公告详情</span>
            <h2 id="official-detail-title">{notice.title}</h2>
          </div>
          <button type="button" className="icon-button" title="关闭详情" aria-label="关闭详情" onClick={onClose}><X size={18} /></button>
        </div>

        <div className="official-detail-meta">
          <span><Globe2 size={14} /> {sourceName}</span>
          <span><CalendarClock size={14} /> 发布于 {formatDate(notice.published_at)}</span>
          <span className={'official-category-tag ' + category.className}><Tag size={12} /> {category.label}</span>
        </div>

        <div className="official-detail-actions">
          <button type="button" className="button button-primary" onClick={() => onAction(!completed)} disabled={busy}>
            {busy ? <LoaderCircle size={15} className="spin" /> : completed ? <RotateCcw size={15} /> : <CheckCircle2 size={15} />}
            {completed ? '撤销完成' : '标记完成'}
          </button>
          <button type="button" className="button button-quiet" onClick={() => onRead(!read)} disabled={busy}>
            {read ? <RotateCcw size={15} /> : <Check size={15} />}
            {read ? '标记未读' : '标记已读'}
          </button>
          <a className="button button-quiet" href={notice.url} target="_blank" rel="noopener noreferrer">
            <ExternalLink size={15} /> 打开原文
          </a>
        </div>

        {error && (
          <div className="official-detail-error" role="alert">
            <AlertTriangle size={15} />
            <span>{error}</span>
            <button type="button" className="button button-quiet" onClick={onRetry} disabled={loading}>重试读取</button>
          </div>
        )}

        <section className="official-detail-section">
          <div className="official-detail-section-label">截止日期</div>
          <div className={'official-deadline-box ' + (deadlineValue(detail) ? 'identified' : '')}>
            <CalendarClock size={20} />
            <div>
              <strong>{deadlineLabel(detail || notice)}</strong>
              <p>{detail?.deadline?.raw || (detail?.detail_fetched_at ? '正文中没有找到可靠截止日期。' : '只在打开详情或主动读取时检查正文。')}</p>
            </div>
          </div>
        </section>

        <section className="official-detail-section">
          <div className="official-detail-section-label">分类判断</div>
          <div className="official-detail-classification">
            <span className={'official-category-tag ' + category.className}>{category.label}</span>
            <span>置信度 {Math.round(Number(detail?.category_confidence ?? notice?.category_confidence ?? 0) * 100)}%</span>
            <p>{detail?.category_evidence || notice?.category_evidence || '暂无分类证据'}</p>
          </div>
        </section>

        <section className="official-detail-section official-detail-summary">
          <div className="official-detail-section-label">公开正文摘要</div>
          {loading ? (
            <div className="official-detail-loading"><LoaderCircle size={18} className="spin" /> 正在读取公开正文</div>
          ) : summary ? (
            <p>{summary}</p>
          ) : (
            <p className="muted-note">尚未读取正文。读取只访问温州大学公开页面，不下载图片和附件。</p>
          )}
        </section>
      </aside>
    </div>
  );
}

export default function OfficialMonitor() {
  const [overview, setOverview] = useState(null);
  const [selectedSource, setSelectedSource] = useState('all');
  const [query, setQuery] = useState('');
  const [range, setRange] = useState('30');
  const [categoryFilter, setCategoryFilter] = useState('all');
  const [readView, setReadView] = useState('all');
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [detailNotice, setDetailNotice] = useState(null);
  const [detail, setDetail] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState('');
  const [sourceLayout, setSourceLayout] = useState(readOfficialSourceLayout);
  const [draggedSourceId, setDraggedSourceId] = useState('');
  const [dropTargetSourceId, setDropTargetSourceId] = useState('');
  const [contextMenu, setContextMenu] = useState(null);
  const dragMovedRef = useRef(false);
  const draggedSourceRef = useRef('');

  const refresh = async (signal) => {
    try {
      const response = await fetch('/api/official/overview', { signal });
      const payload = await response.json();
      if (!response.ok) throw new Error(payload.detail || '无法读取官网监测状态');
      setOverview(payload);
      setError('');
    } catch (err) {
      if (err.name !== 'AbortError') setError(err.message);
    }
  };

  useEffect(() => {
    const controller = new AbortController();
    refresh(controller.signal);
    return () => controller.abort();
  }, []);

  useEffect(() => {
    const timer = window.setInterval(() => refresh(), overview?.scan?.running ? 2000 : 30000);
    return () => window.clearInterval(timer);
  }, [overview?.scan?.running]);

  const sources = overview?.sources || [];
  const sourceIds = useMemo(() => sources.map((source) => source.id), [sources]);
  const summary = overview?.summary || {};
  const scan = overview?.scan || {};
  const currentSource = sources.find((source) => source.id === selectedSource);
  const currentUnread = currentSource ? currentSource.unread_count : summary.unread_count;

  useEffect(() => {
    if (!sourceIds.length) return;
    setSourceLayout((current) => {
      const next = normalizeOfficialSourceLayout(current, sourceIds);
      if (sameOfficialSourceLayout(current, next)) return current;
      saveOfficialSourceLayout(next);
      return next;
    });
  }, [sourceIds]);

  useEffect(() => {
    if (!contextMenu) return undefined;
    const closeMenu = () => setContextMenu(null);
    const handleKeyDown = (event) => {
      if (event.key === 'Escape') closeMenu();
    };
    document.addEventListener('pointerdown', closeMenu);
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('pointerdown', closeMenu);
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [contextMenu]);

  const orderedSources = useMemo(() => {
    const sourceIndex = new Map(sources.map((source, index) => [source.id, index]));
    const orderIndex = new Map(sourceLayout.order.map((id, index) => [id, index]));
    const pinned = new Set(sourceLayout.pinned);
    return [...sources].sort((left, right) => {
      const leftPinned = pinned.has(left.id);
      const rightPinned = pinned.has(right.id);
      if (leftPinned !== rightPinned) return leftPinned ? -1 : 1;
      const leftOrder = orderIndex.get(left.id);
      const rightOrder = orderIndex.get(right.id);
      if (leftOrder !== undefined && rightOrder !== undefined) return leftOrder - rightOrder;
      if (leftOrder !== undefined) return -1;
      if (rightOrder !== undefined) return 1;
      return sourceIndex.get(left.id) - sourceIndex.get(right.id);
    });
  }, [sources, sourceLayout]);

  const updateSourceLayout = (updater) => {
    setSourceLayout((current) => {
      const next = typeof updater === 'function' ? updater(current) : updater;
      saveOfficialSourceLayout(next);
      return next;
    });
  };

  const togglePinnedSource = (sourceId) => {
    updateSourceLayout((current) => {
      const pinned = current.pinned.includes(sourceId)
        ? current.pinned.filter((id) => id !== sourceId)
        : [...current.pinned, sourceId];
      return { ...current, pinned };
    });
    setContextMenu(null);
  };

  const handleSourceDragStart = (event, sourceId) => {
    dragMovedRef.current = false;
    draggedSourceRef.current = sourceId;
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', sourceId);
    setDraggedSourceId(sourceId);
    setDropTargetSourceId('');
    setContextMenu(null);
  };

  const handleSourceDrop = (targetId) => {
    const sourceId = draggedSourceRef.current || draggedSourceId;
    if (!sourceId || sourceId === targetId) return;
    dragMovedRef.current = true;
    updateSourceLayout((current) => {
      const sourcePinned = current.pinned.includes(sourceId);
      const targetPinned = current.pinned.includes(targetId);
      if (sourcePinned !== targetPinned) return current;
      const order = [...current.order];
      if (!order.includes(sourceId)) order.push(sourceId);
      if (!order.includes(targetId)) order.push(targetId);
      const from = order.indexOf(sourceId);
      order.splice(from, 1);
      const targetIndex = order.indexOf(targetId);
      order.splice(targetIndex, 0, sourceId);
      return { ...current, order };
    });
    setDraggedSourceId('');
    setDropTargetSourceId('');
  };

  const handleSourceDragEnd = () => {
    draggedSourceRef.current = '';
    setDraggedSourceId('');
    setDropTargetSourceId('');
  };

  const scopedNotices = useMemo(() => {
    const floor = range === 'all' ? '' : dateFloor(Number(range));
    const needle = query.trim().toLowerCase();
    return (overview?.notices || []).filter((notice) => {
      if (selectedSource !== 'all' && notice.source_id !== selectedSource) return false;
      if (floor && (notice.published_at || notice.first_seen?.slice(0, 10) || '') < floor) return false;
      if (categoryFilter !== 'all' && (notice.category || 'unknown') !== categoryFilter) return false;
      if (needle && !`${notice.title} ${notice.source_name} ${notice.column} ${notice.category}`.toLowerCase().includes(needle)) return false;
      return true;
    });
  }, [overview?.notices, selectedSource, query, range, categoryFilter]);
  const unreadCount = scopedNotices.filter((notice) => notice.read === false).length;
  const readCount = scopedNotices.length - unreadCount;
  const filteredNotices = scopedNotices.filter((notice) => (
    readView === 'all' || (readView === 'unread' ? notice.read === false : notice.read !== false)
  ));

  const startScan = async (all = false) => {
    setBusy('scan');
    setError('');
    try {
      await postJson('/api/official/scan', all || selectedSource === 'all' ? {} : { source_ids: [selectedSource] });
      await refresh();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy('');
    }
  };

  const setRead = async (notice, read) => {
    const key = notice ? notice.url : 'all';
    setBusy(key);
    setError('');
    try {
      await postJson('/api/official/read', {
        ...(selectedSource !== 'all' && !notice ? { source_id: selectedSource } : {}),
        ...(notice ? { source_id: notice.source_id, url: notice.url } : {}),
        read,
      });
      await refresh();
      if (detailNotice?.url === notice?.url) {
        setDetail((current) => current ? { ...current, read } : current);
      }
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy('');
    }
  };

  const loadDetail = async (notice) => {
    setDetailNotice(notice);
    setDetail(notice);
    setDetailLoading(true);
    setDetailError('');
    try {
      const params = new URLSearchParams({ source_id: notice.source_id, url: notice.url });
      const response = await fetch('/api/official/notice?' + params.toString());
      const payload = await response.json();
      if (!response.ok) throw new Error(payload.detail || '正文读取失败');
      setDetail(payload);
      if (notice.read === false) await setRead(notice, true);
    } catch (err) {
      setDetailError(err.message);
    } finally {
      setDetailLoading(false);
    }
  };

  const setCompleted = async (completed) => {
    if (!detailNotice) return;
    setBusy('detail-action');
    setDetailError('');
    try {
      const payload = await postJson('/api/official/action', {
        source_id: detailNotice.source_id,
        url: detailNotice.url,
        completed,
      });
      setDetail((current) => ({ ...(current || detailNotice), ...payload }));
      await refresh();
    } catch (err) {
      setDetailError(err.message);
    } finally {
      setBusy('');
    }
  };

  const setIntervalHours = async (hours) => {
    setBusy('interval');
    try {
      await postJson('/api/official/settings', { auto_interval_hours: Number(hours) });
      await refresh();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy('');
    }
  };

  const categoryCounts = summary.category_counts || {};
  const categoryOptions = Object.keys(CATEGORY_META).filter((value) => categoryCounts[value] || value === 'unknown');
  const contextSource = contextMenu ? sources.find((source) => source.id === contextMenu.sourceId) : null;

  return (
    <>
      <header className="topbar official-topbar">
        <div className="group-context">
          <div className="context-icon"><Globe2 size={18} /></div>
          <div><span className="context-label">公开来源</span><strong>温州大学 · 学院官网</strong></div>
        </div>
        <div className="topbar-actions">
          <label className="official-interval-label" htmlFor="official-interval">自动检查</label>
          <select id="official-interval" className="official-interval" value={summary.auto_interval_hours ?? 12} onChange={(event) => setIntervalHours(event.target.value)} disabled={busy === 'interval'}>
            <option value={0}>关闭</option>
            <option value={6}>每 6 小时</option>
            <option value={12}>每 12 小时</option>
            <option value={24}>每 24 小时</option>
          </select>
          <button type="button" className="button button-primary" onClick={() => startScan(true)} disabled={scan.running || busy === 'scan'}>
            {scan.running || busy === 'scan' ? <LoaderCircle size={16} className="spin" /> : <RefreshCw size={16} />}
            {scan.running ? '检查中' : '检查全部'}
          </button>
        </div>
      </header>

      <section className="page-heading official-heading">
        <div>
          <p className="eyebrow">OFFICIAL SOURCES / 02</p>
          <h1>学院官网监测</h1>
          <p className="heading-subtitle">上次全量检查 {shortTime(summary.last_full_scan_at)} · 只读取温州大学公开来源</p>
        </div>
        <a className="official-catalog-link" href={overview?.catalog_url || 'https://www.wzu.edu.cn/xxgk/xysz.htm'} target="_blank" rel="noopener noreferrer">
          官方学院名录 <ArrowUpRight size={16} />
        </a>
      </section>

      {error && <div className="error-banner official-error" role="alert"><AlertTriangle size={16} /><span>{error}</span><button type="button" className="icon-button small" title="关闭" aria-label="关闭错误提示" onClick={() => setError('')}><X size={14} /></button></div>}
      {scan.error && <div className="error-banner official-error" role="alert"><AlertTriangle size={16} /><span>{scan.error}</span></div>}

      {scan.running && (
        <section className="official-progress" aria-live="polite">
          <div><strong>正在检查 {scan.current || '学院站点'}</strong><span>{scan.done || 0} / {scan.total || 0} · 失败 {scan.failed || 0}</span></div>
          <div className="official-progress-track"><span style={{ width: `${scan.total ? (scan.done / scan.total) * 100 : 0}%` }} /></div>
        </section>
      )}

      <div className="official-summarybar">
        <span><strong>{summary.checked_count || 0} / {summary.source_count || 0}</strong> 学院已检查</span>
        <span className="official-summary-unread"><strong>{summary.unread_count || 0}</strong> 条累计未读</span>
        <span><strong>{summary.read_count || 0}</strong> 条累计已读</span>
        <span className="official-summary-completed"><strong>{summary.completed_count || 0}</strong> 条已完成</span>
        <span><strong>{summary.error_count || 0}</strong> 个来源需关注</span>
      </div>

      <div className="official-workspace">
        <aside className="official-sources" aria-label="学院来源">
          <div className="official-panel-head"><h2>来源</h2><span>{sources.length} 个学院</span></div>
          <div className="official-source-scroll">
            <button type="button" className={'official-source ' + (selectedSource === 'all' ? 'active' : '')} onClick={() => setSelectedSource('all')}>
              <span className="official-source-main"><strong>全部学院</strong><small>聚合公告</small></span>
              <b>{summary.unread_count || 0}</b>
            </button>
            {orderedSources.map((source, index) => {
              const isPinned = sourceLayout.pinned.includes(source.id);
              const isDragging = draggedSourceId === source.id;
              const isDropTarget = dropTargetSourceId === source.id && draggedSourceId !== source.id;
              return (
              <button
                type="button"
                key={source.id}
                className={[
                  'official-source',
                  selectedSource === source.id ? 'active' : '',
                  isDragging ? 'dragging' : '',
                  isDropTarget ? 'drop-target' : '',
                ].filter(Boolean).join(' ')}
                draggable
                aria-grabbed={isDragging}
                onClick={() => {
                  if (dragMovedRef.current) {
                    dragMovedRef.current = false;
                    return;
                  }
                  setSelectedSource(source.id);
                }}
                onDragStart={(event) => handleSourceDragStart(event, source.id)}
                onDragEnter={() => {
                  const activeSourceId = draggedSourceRef.current || draggedSourceId;
                  if (activeSourceId && activeSourceId !== source.id) setDropTargetSourceId(source.id);
                }}
                onDragOver={(event) => {
                  event.preventDefault();
                  event.dataTransfer.dropEffect = 'move';
                }}
                onDrop={(event) => {
                  event.preventDefault();
                  handleSourceDrop(source.id);
                }}
                onDragEnd={handleSourceDragEnd}
                onContextMenu={(event) => {
                  event.preventDefault();
                  const menuWidth = 184;
                  const menuHeight = 92;
                  setContextMenu({
                    sourceId: source.id,
                    x: Math.max(8, Math.min(event.clientX, window.innerWidth - menuWidth - 8)),
                    y: Math.max(8, Math.min(event.clientY, window.innerHeight - menuHeight - 8)),
                  });
                }}
                title={`${source.error ? `${source.error} · ` : ''}${source.name} · 拖动排序，右键${isPinned ? '取消置顶' : '置顶'}`}
              >
                <GripVertical className="official-source-drag-handle" size={14} aria-hidden="true" />
                <span className="official-source-index">{String(index + 1).padStart(2, '0')}</span>
                <span className="official-source-main"><strong>{source.name}</strong><small><i className={'official-status-dot ' + (STATUS[source.status]?.className || 'idle')} />{STATUS[source.status]?.label || source.status} · {source.notice_count} 条</small></span>
                <span className="official-source-trailing">
                  {isPinned && <Pin className="official-source-pin" size={14} aria-hidden="true" />}
                  {source.unread_count > 0 && <b>{source.unread_count}</b>}
                </span>
              </button>
              );
            })}
          </div>
        </aside>

        <section className="official-feed" aria-label="学院公告">
          <div className="official-feed-head">
            <div><h2>{currentSource?.name || '全部学院公告'}</h2><span>{currentSource ? `${STATUS[currentSource.status]?.label || ''} · 上次检查 ${shortTime(currentSource.checked_at)}` : `${filteredNotices.length} 条匹配公告`}</span></div>
            <div className="official-feed-actions">
              {currentSource && <a href={currentSource.list_url || currentSource.home} target="_blank" rel="noopener noreferrer" className="official-source-link" title="打开学院官网" aria-label="打开学院官网"><ArrowUpRight size={16} /></a>}
              <button type="button" className="icon-button" title={currentSource ? '检查当前学院' : '检查全部学院'} aria-label={currentSource ? '检查当前学院' : '检查全部学院'} onClick={() => startScan(false)} disabled={scan.running || busy === 'scan'}><RefreshCw size={16} className={scan.running ? 'spin' : ''} /></button>
            </div>
          </div>

          {currentSource?.error && <div className="official-source-error"><AlertTriangle size={15} /> {currentSource.error}</div>}

          <div className="official-filterbar">
            <div className="official-range" role="group" aria-label="发布日期范围">
              {RANGES.map((option) => <button type="button" key={option.id} className={range === option.id ? 'active' : ''} onClick={() => setRange(option.id)}>{option.label}</button>)}
            </div>
            <label className="official-category-filter">
              <Tag size={14} />
              <span className="visually-hidden">公告分类</span>
              <select aria-label="按公告分类筛选" value={categoryFilter} onChange={(event) => setCategoryFilter(event.target.value)}>
                <option value="all">全部分类</option>
                {categoryOptions.map((value) => <option key={value} value={value}>{categoryMeta(value).label} · {categoryCounts[value] || 0}</option>)}
              </select>
            </label>
            <div className="official-search"><Search size={15} /><input aria-label="搜索学院公告" placeholder="搜索标题、学院或栏目" value={query} onChange={(event) => setQuery(event.target.value)} />{query && <button type="button" title="清空搜索" aria-label="清空搜索" onClick={() => setQuery('')}><X size={13} /></button>}</div>
            <button type="button" className="official-mark-all" onClick={() => setRead(null, true)} disabled={!currentUnread || busy === 'all'} title="将当前来源的公告全部标记已读"><CheckCheck size={16} /> 全部已读</button>
          </div>

          <div className="official-read-tabs" role="tablist" aria-label="公告阅读状态">
            {[
              { id: 'all', label: '全部', count: scopedNotices.length },
              { id: 'unread', label: '未读', count: unreadCount },
              { id: 'read', label: '已读完', count: readCount },
            ].map((view) => (
              <button type="button" key={view.id} role="tab" aria-selected={readView === view.id} className={readView === view.id ? 'active' : ''} onClick={() => setReadView(view.id)}>
                {view.label}<span>{view.count}</span>
              </button>
            ))}
            <small>{categoryFilter === 'all' ? '全部分类' : categoryMeta(categoryFilter).label} · 当前筛选</small>
          </div>

          <div className="official-feed-columns"><span>发布日期</span><span>学院 / 栏目</span><span>公告标题</span><span>截止日期</span><span>操作</span></div>
          <div className="official-feed-scroll">
            {filteredNotices.map((notice) => {
              const category = categoryMeta(notice.category);
              return (
                <div className={'official-notice ' + (notice.read === false ? 'unread' : '')} key={`${notice.source_id}:${notice.url}`}>
                  <time>{formatDate(notice.published_at)}</time>
                  <div className="official-notice-origin"><strong>{notice.source_name}</strong><small>{notice.column || '公告'}</small><span className={'official-category-tag ' + category.className}>{category.label}</span></div>
                  <div className="official-notice-title">
                    <a href={notice.url} target="_blank" rel="noopener noreferrer" title={notice.title} onClick={() => { if (notice.read === false) setRead(notice, true); }}>{notice.title}<ArrowUpRight size={14} /></a>
                    {notice.completed && <span className="official-completed-mark"><CheckCircle2 size={13} /> 已完成</span>}
                  </div>
                  <div className={'official-notice-deadline ' + (deadlineValue(notice) ? 'identified' : '')}>
                    <CalendarClock size={14} />
                    <span>{deadlineLabel(notice)}</span>
                  </div>
                  <div className="official-notice-actions">
                    <button type="button" className="icon-button small" title="查看正文与截止日期" aria-label={`查看正文与截止日期：${notice.title}`} onClick={() => loadDetail(notice)}><FileSearch size={15} /></button>
                    <button type="button" className="icon-button small" title={notice.read === false ? '标记已读' : '标记未读'} aria-label={`${notice.read === false ? '标记已读' : '标记未读'}：${notice.title}`} onClick={() => setRead(notice, notice.read === false)} disabled={busy === notice.url}>
                      {notice.read === false ? <Check size={15} /> : <RotateCcw size={14} />}
                    </button>
                  </div>
                </div>
              );
            })}
            {!filteredNotices.length && (
              <div className="official-empty">
                <Globe2 size={26} strokeWidth={1.5} />
                <h3>{!summary.checked_count ? '尚未检查学院官网' : readView === 'read' && !readCount ? '还没有已读完的公告' : readView === 'unread' && !unreadCount ? '未读已清空' : '没有匹配的公告'}</h3>
                {!summary.checked_count && <button type="button" className="button button-primary" onClick={() => startScan(true)} disabled={scan.running}>检查全部学院</button>}
                {summary.checked_count > 0 && (query || readView !== 'all' || range !== 'all' || categoryFilter !== 'all') && <button type="button" className="button button-quiet" onClick={() => { setQuery(''); setReadView('all'); setRange('all'); setCategoryFilter('all'); }}>清除筛选</button>}
              </div>
            )}
          </div>
          <div className="official-feed-footer"><span>显示 {filteredNotices.length} 条 · 列表只保存公开页面元数据，正文按需读取</span></div>
        </section>
      </div>

      {contextSource && contextMenu && (
        <div
          className="official-source-context-menu"
          role="menu"
          aria-label={`${contextSource.name}来源操作`}
          style={{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }}
          onPointerDown={(event) => event.stopPropagation()}
        >
          <div className="official-source-context-menu-label" title={contextSource.name}>{contextSource.name}</div>
          <button type="button" role="menuitem" onClick={() => togglePinnedSource(contextSource.id)}>
            {sourceLayout.pinned.includes(contextSource.id) ? <PinOff size={15} /> : <Pin size={15} />}
            {sourceLayout.pinned.includes(contextSource.id) ? '取消置顶' : '置顶学院'}
          </button>
        </div>
      )}

      {detailNotice && (
        <OfficialDetail
          notice={detailNotice}
          detail={detail || detailNotice}
          loading={detailLoading}
          error={detailError}
          busy={busy === 'detail-action' || busy === detailNotice.url}
          onClose={() => { setDetailNotice(null); setDetail(null); setDetailError(''); }}
          onRetry={() => loadDetail(detailNotice)}
          onRead={(read) => setRead(detailNotice, read)}
          onAction={setCompleted}
        />
      )}
    </>
  );
}
