import { useVirtualizer } from "@tanstack/react-virtual";
import { useEffect, useMemo, useRef, useState } from "react";
import { describeClose, describeQueryType, formatBytes, formatDuration, formatRate, formatTime } from "../log/format";
import type { LogRow, LogTotals, RowKind } from "../log/store";
import type { Controller, ShiraState } from "../shira/controller";

const REFRESH_MILLIS = 250;
const ROW_HEIGHT = 44;

type StateFilter = "all" | "active" | "failed";
type KindFilter = "all" | RowKind;

interface Props {
	controller: Controller;
	state: ShiraState;
}

/** Follows the log a few times a second, whatever's happening underneath. */
function useLogSnapshot(controller: Controller, paused: boolean) {
	const { log } = controller;
	const [rows, setRows] = useState<LogRow[]>([]);
	const [totals, setTotals] = useState<LogTotals>(() => log.totals(Date.now()));
	const [now, setNow] = useState(() => Date.now());
	const seen = useRef(-1);

	useEffect(() => {
		const refresh = () => {
			const time = Date.now();
			setNow(time);
			setTotals(log.totals(time));
			if (paused || seen.current === log.version) return;
			seen.current = log.version;
			// A new array, because React won't redraw for rows that only changed inside.
			setRows(log.rows.map((row) => ({ ...row })).reverse());
		};
		refresh();
		const timer = setInterval(refresh, REFRESH_MILLIS);
		return () => clearInterval(timer);
	}, [log, paused]);

	return { rows, totals, now, redraw: () => (seen.current = -1) };
}

export function Monitor({ controller, state }: Props) {
	const [paused, setPaused] = useState(false);
	const [search, setSearch] = useState("");
	const [stateFilter, setStateFilter] = useState<StateFilter>("all");
	const [kindFilter, setKindFilter] = useState<KindFilter>("all");
	const [selected, setSelected] = useState<number | null>(null);
	const { rows, totals, now, redraw } = useLogSnapshot(controller, paused);

	const visible = useMemo(() => {
		const words = search.trim().toLowerCase().split(/\s+/).filter(Boolean);
		return rows.filter((row) => {
			if (stateFilter === "active" && row.state !== "open") return false;
			if (stateFilter === "failed" && row.state !== "failed") return false;
			if (kindFilter !== "all" && row.kind !== kindFilter) return false;
			if (words.length === 0) return true;
			const text = `${row.name} ${row.host}:${row.port} ${controller.log.appName(row)} ${row.kind}`.toLowerCase();
			return words.every((word) => text.includes(word));
		});
	}, [rows, search, stateFilter, kindFilter, controller.log]);

	const selectedRow = selected === null ? null : (rows.find((row) => row.id === selected) ?? null);

	return (
		<main className="monitor">
			<StatusStrip controller={controller} state={state} totals={totals} />
			<LinkBanner controller={controller} state={state} />
			<div className="toolbar">
				<mdui-text-field
					className="search"
					variant="outlined"
					placeholder="Search by name, address, app or port"
					value={search}
					clearable
					onInput={(event: React.FormEvent<HTMLElement>) =>
						setSearch((event.target as HTMLInputElement).value)
					}
				>
					<mdui-icon-search slot="icon" />
				</mdui-text-field>
				<fieldset className="filter-group" aria-label="Filter by state">
					<FilterChip label="All" selected={stateFilter === "all"} onClick={() => setStateFilter("all")} />
					<FilterChip
						label="Active"
						selected={stateFilter === "active"}
						onClick={() => setStateFilter("active")}
					/>
					<FilterChip
						label="Failed"
						selected={stateFilter === "failed"}
						onClick={() => setStateFilter("failed")}
					/>
				</fieldset>
				<fieldset className="filter-group" aria-label="Filter by protocol">
					<FilterChip label="Any" selected={kindFilter === "all"} onClick={() => setKindFilter("all")} />
					<FilterChip label="TCP" selected={kindFilter === "tcp"} onClick={() => setKindFilter("tcp")} />
					<FilterChip label="UDP" selected={kindFilter === "udp"} onClick={() => setKindFilter("udp")} />
					<FilterChip label="DNS" selected={kindFilter === "dns"} onClick={() => setKindFilter("dns")} />
				</fieldset>
				<div className="toolbar-actions">
					<mdui-tooltip
						content={paused ? "Carry on showing new connections" : "Stop showing new connections for now"}
					>
						<mdui-button-icon onClick={() => setPaused(!paused)} aria-label={paused ? "Resume" : "Pause"}>
							{paused ? <mdui-icon-play-arrow /> : <mdui-icon-pause />}
						</mdui-button-icon>
					</mdui-tooltip>
					<mdui-tooltip content="Clear the log">
						<mdui-button-icon
							aria-label="Clear the log"
							onClick={() => {
								controller.log.clear();
								redraw();
							}}
						>
							<mdui-icon-delete-outline />
						</mdui-button-icon>
					</mdui-tooltip>
					<mdui-tooltip content="Save the log as JSON">
						<mdui-button-icon aria-label="Save the log as JSON" onClick={() => saveLog(controller)}>
							<mdui-icon-download />
						</mdui-button-icon>
					</mdui-tooltip>
				</div>
			</div>
			<LogTable
				rows={visible}
				now={now}
				controller={controller}
				isEmpty={rows.length === 0}
				onSelect={setSelected}
			/>
			{selectedRow ? (
				<RowDetails row={selectedRow} now={now} controller={controller} onClose={() => setSelected(null)} />
			) : null}
		</main>
	);
}

function FilterChip({ label, selected, onClick }: { label: string; selected: boolean; onClick: () => void }) {
	return (
		<mdui-chip variant="filter" selected={selected} onClick={onClick}>
			{label}
		</mdui-chip>
	);
}

function StatusStrip({ controller, state, totals }: Props & { totals: LogTotals }) {
	return (
		<section className="status-strip">
			<div className="status-identity">
				<div className="status-line">
					<span className={`status-dot status-dot-${state.link}`} />
					<strong>{describeLink(state)}</strong>
				</div>
				<div className="status-detail">
					<span>
						<mdui-icon-phone-android /> {state.phone ?? "No phone"}
					</span>
					<span>
						<mdui-icon-dns /> {state.serverLabel}
						{state.wispVersion ? ` · Wisp v${state.wispVersion}` : ""}
						{state.udp ? "" : " · no UDP"}
					</span>
				</div>
			</div>
			<Stat
				icon={<mdui-icon-arrow-downward />}
				label="Download"
				value={formatRate(totals.downRate)}
				detail={formatBytes(totals.down)}
			/>
			<Stat
				icon={<mdui-icon-arrow-upward />}
				label="Upload"
				value={formatRate(totals.upRate)}
				detail={formatBytes(totals.up)}
			/>
			<Stat
				icon={<mdui-icon-lan />}
				label="Active"
				value={String(totals.active)}
				detail={`${totals.total} total, ${totals.failed} failed`}
			/>
			<mdui-button variant="tonal" onClick={() => controller.deactivate()}>
				<mdui-icon-power-settings-new slot="icon" />
				Deactivate
			</mdui-button>
		</section>
	);
}

function describeLink(state: ShiraState): string {
	switch (state.link) {
		case "live":
			return "Connected";
		case "server-lost":
			return "Reconnecting to the server";
		case "phone-lost":
			return "Phone disconnected";
		case "connecting":
			return "Connecting";
		default:
			return "Waiting for the app";
	}
}

function LinkBanner({ controller, state }: Props) {
	if (state.link === "phone-lost") {
		return (
			<div className="link-banner" role="status">
				<mdui-icon-usb />
				<span>Your phone was disconnected. Plug it back in and this carries on by itself.</span>
				<mdui-button variant="text" onClick={() => controller.reconnect()}>
					Connect it again
				</mdui-button>
			</div>
		);
	}
	if (state.link === "server-lost") {
		return (
			<div className="link-banner" role="status">
				<mdui-icon-dns />
				<span>
					Lost the connection to {state.serverLabel}. Your phone's normal network is back until this
					reconnects.
				</span>
			</div>
		);
	}
	return state.link === "live" ? (
		<p className="keep-open">Keep this tab open. Your phone goes back to its normal network when you close it.</p>
	) : null;
}

function Stat({ icon, label, value, detail }: { icon: React.ReactNode; label: string; value: string; detail: string }) {
	return (
		<div className="stat">
			<span className="stat-label">
				{icon} {label}
			</span>
			<span className="stat-value">{value}</span>
			<span className="stat-detail">{detail}</span>
		</div>
	);
}

interface TableProps {
	rows: LogRow[];
	now: number;
	controller: Controller;
	isEmpty: boolean;
	onSelect: (id: number) => void;
}

function LogTable({ rows, now, controller, isEmpty, onSelect }: TableProps) {
	const scroller = useRef<HTMLDivElement>(null);
	const virtual = useVirtualizer({
		count: rows.length,
		getScrollElement: () => scroller.current,
		estimateSize: () => ROW_HEIGHT,
		overscan: 12,
	});
	return (
		<section className="log" aria-label="Connections">
			<div className="log-row log-head">
				<span>Time</span>
				<span>App</span>
				<span>Type</span>
				<span>Destination</span>
				<span>Status</span>
				<span className="number">Up</span>
				<span className="number">Down</span>
				<span className="number">Time open</span>
			</div>
			<div className="log-body" ref={scroller}>
				{rows.length === 0 ? (
					<div className="log-empty">
						<mdui-icon-lan />
						<p>
							{isEmpty
								? "Nothing yet. Open an app on your phone, and its connections appear here."
								: "Nothing matches that."}
						</p>
					</div>
				) : (
					<div style={{ height: virtual.getTotalSize(), position: "relative" }}>
						{virtual.getVirtualItems().map((item) => {
							const row = rows[item.index];
							if (!row) return null;
							return (
								<button
									type="button"
									key={row.id}
									className={`log-row log-entry log-entry-${row.state}`}
									style={{ transform: `translateY(${item.start}px)`, height: ROW_HEIGHT }}
									onClick={() => onSelect(row.id)}
								>
									<span className="mono muted">{formatTime(row.openedAt)}</span>
									<span className="ellipsis" title={controller.log.appName(row)}>
										{row.kind === "dns" ? (
											<span className="muted">Resolver</span>
										) : (
											controller.log.appName(row)
										)}
									</span>
									<span>
										<span className={`kind kind-${row.kind}`}>{row.kind.toUpperCase()}</span>
									</span>
									<span className="ellipsis" title={destinationTitle(row)}>
										{destination(row)}
									</span>
									<span className="ellipsis status-cell">
										<span className={`status-pip status-pip-${row.state}`} />
										{statusText(row)}
									</span>
									<span className="number mono">{formatBytes(row.up)}</span>
									<span className="number mono">{formatBytes(row.down)}</span>
									<span className="number mono muted">
										{formatDuration((row.closedAt ?? now) - row.openedAt)}
									</span>
								</button>
							);
						})}
					</div>
				)}
			</div>
		</section>
	);
}

function destination(row: LogRow) {
	if (row.kind === "dns") {
		return (
			<>
				{row.name || "Unknown name"} <span className="muted">{describeQueryType(row.queryType)}</span>
			</>
		);
	}
	return (
		<>
			{row.name || row.host}
			<span className="muted">:{row.port}</span>
		</>
	);
}

function destinationTitle(row: LogRow): string {
	return row.name ? `${row.name} (${row.host}:${row.port})` : `${row.host}:${row.port}`;
}

function statusText(row: LogRow): string {
	if (row.state === "open") return "Open";
	return describeClose(row.closeReason);
}

function RowDetails({
	row,
	now,
	controller,
	onClose,
}: {
	row: LogRow;
	now: number;
	controller: Controller;
	onClose: () => void;
}) {
	const dialog = useRef<HTMLElement>(null);
	useEffect(() => {
		// React can't listen for this event by name, because of how it spells custom element events.
		const element = dialog.current;
		element?.addEventListener("closed", onClose);
		return () => element?.removeEventListener("closed", onClose);
	}, [onClose]);
	return (
		<mdui-dialog ref={dialog as never} open close-on-esc close-on-overlay-click headline={row.name || row.host}>
			<dl className="details">
				<dt>App</dt>
				<dd>{row.kind === "dns" ? "The phone's resolver" : controller.log.appName(row)}</dd>
				{row.uid >= 0 ? (
					<>
						<dt>Android user ID</dt>
						<dd>{row.uid}</dd>
					</>
				) : null}
				<dt>Type</dt>
				<dd>
					{row.kind === "dns" ? `DNS ${describeQueryType(row.queryType)} lookup` : row.kind.toUpperCase()}
				</dd>
				<dt>Address</dt>
				<dd className="mono">
					{row.host}:{row.port}
				</dd>
				<dt>Status</dt>
				<dd>{statusText(row)}</dd>
				<dt>Opened</dt>
				<dd className="mono">{formatTime(row.openedAt)}</dd>
				<dt>Open for</dt>
				<dd>{formatDuration((row.closedAt ?? now) - row.openedAt)}</dd>
				<dt>Uploaded</dt>
				<dd>{formatBytes(row.up)}</dd>
				<dt>Downloaded</dt>
				<dd>{formatBytes(row.down)}</dd>
				<dt>Wisp stream</dt>
				<dd className="mono">{row.stream}</dd>
			</dl>
			<mdui-button slot="action" variant="text" onClick={onClose}>
				Close
			</mdui-button>
		</mdui-dialog>
	);
}

function saveLog(controller: Controller) {
	const url = URL.createObjectURL(new Blob([controller.log.toJson()], { type: "application/json" }));
	const link = document.createElement("a");
	link.href = url;
	link.download = `shira-log-${new Date().toISOString().replaceAll(":", "-")}.json`;
	link.click();
	URL.revokeObjectURL(url);
}
