import { useCallback, useEffect, useState } from "react";
import { Search, Wrench } from "lucide-react";
import { toast } from "sonner";

import { RelativeTime } from "@/components/RelativeTime";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from "@/components/ui/table";
import { cn } from "@/lib/utils";
import {
  getAgentToolCallAuditPage,
  type AgentToolCallAuditItem
} from "@/services/agentToolCallAuditService";
import { getErrorMessage } from "@/utils/error";

const PAGE_SIZE_OPTIONS = [10, 20, 50];
const ALL_VALUE = "__all__";

// decision 七值词汇（与后端 AgentToolCallDecision 一一对应，T2 权限面复用同一套）
const DECISION_OPTIONS = [
  { value: "ALLOW", label: "允许" },
  { value: "MASKED", label: "遮蔽" },
  { value: "ERROR", label: "错误" },
  { value: "RULE_DENY", label: "规则拒绝" },
  { value: "ASK_DENIED", label: "用户拒绝" },
  { value: "ASK_APPROVED", label: "卡片批准" },
  { value: "INTERRUPTED", label: "中断" }
];

const decisionBadgeClass = (decision: string) => {
  switch (decision) {
    case "ALLOW":
      return "border-[#b7eb8f] bg-[#f6ffed] text-[#52C41A]";
    case "MASKED":
      return "border-slate-200 bg-slate-50 text-slate-600";
    case "ERROR":
      return "border-[#ffa39e] bg-[#fff1f0] text-[#F522D]";
    case "RULE_DENY":
      return "border-[#ffd591] bg-[#fff7e6] text-[#D46B08]";
    case "ASK_DENIED":
      return "border-[#ffadd2] bg-[#fff0f6] text-[#EB2F96]";
    case "ASK_APPROVED":
      return "border-[#91d5ff] bg-[#e6f7ff] text-[#1890FF]";
    case "INTERRUPTED":
      return "border-[#d9d9d9] bg-[#fafafa] text-[#8c8c8c]";
    default:
      return "border-slate-200 bg-slate-50 text-slate-600";
  }
};

const decisionLabel = (decision: string) =>
  DECISION_OPTIONS.find((item) => item.value === decision)?.label || decision;

const pad2 = (value: number) => String(value).padStart(2, "0");

const toDateInput = (date: Date) =>
  `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`;

// 默认查询最近一个月的数据
const defaultDateRange = () => {
  const end = new Date();
  const begin = new Date();
  begin.setMonth(begin.getMonth() - 1);
  return { beginTime: toDateInput(begin), endTime: toDateInput(end) };
};

const createDefaultFilters = () => ({
  userId: "",
  toolName: "",
  decision: "",
  ...defaultDateRange()
});

export function AgentToolCallAuditPage() {
  const [pageNo, setPageNo] = useState(1);
  const [pageSize, setPageSize] = useState(PAGE_SIZE_OPTIONS[0]);
  const [pageData, setPageData] = useState<{
    records: AgentToolCallAuditItem[];
    total: number;
    pages: number;
    current: number;
  } | null>(null);
  const [loading, setLoading] = useState(true);
  const [filters, setFilters] = useState(createDefaultFilters);
  const [query, setQuery] = useState(filters);

  const records = pageData?.records || [];
  const total = pageData?.total || 0;
  const pages = pageData?.pages || 1;
  const current = pageData?.current || pageNo;
  const rangeStart = total === 0 ? 0 : (current - 1) * pageSize + 1;
  const rangeEnd = total === 0 ? 0 : Math.min((current - 1) * pageSize + records.length, total);

  const loadData = useCallback(
    async (currentPage: number, nextQuery: typeof query, size: number) => {
      try {
        setLoading(true);
        setPageData(
          await getAgentToolCallAuditPage({
            current: currentPage,
            size,
            userId: nextQuery.userId.trim() || undefined,
            toolName: nextQuery.toolName.trim() || undefined,
            decision: nextQuery.decision || undefined,
            beginTime: nextQuery.beginTime ? `${nextQuery.beginTime} 00:00:00` : undefined,
            endTime: nextQuery.endTime ? `${nextQuery.endTime} 23:59:59` : undefined
          })
        );
      } catch (error) {
        toast.error(getErrorMessage(error, "加载工具调用审计失败"));
        console.error(error);
      } finally {
        setLoading(false);
      }
    },
    []
  );

  useEffect(() => {
    loadData(pageNo, query, pageSize);
  }, [loadData, pageNo, query, pageSize]);

  const handleSearch = () => {
    setPageNo(1);
    setQuery({ ...filters });
  };

  const handleReset = () => {
    const next = createDefaultFilters();
    setFilters(next);
    setPageNo(1);
    setQuery(next);
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">工具调用审计</h1>
          <p className="admin-page-subtitle">Agent 工具调用的谁/何时/哪个工具/入参摘要与结果状态（90 天保留）</p>
        </div>
      </div>

      <div className="rounded-xl border border-slate-200 bg-white p-3">
        <div className="flex flex-col gap-2 lg:flex-row lg:flex-wrap lg:items-center">
          <Input
            className="h-10 w-[160px] border-slate-200 text-sm focus-visible:border-slate-200 focus-visible:ring-0 focus-visible:ring-offset-0"
            value={filters.userId}
            onChange={(event) => setFilters((prev) => ({ ...prev, userId: event.target.value }))}
            onKeyDown={(event) => event.key === "Enter" && handleSearch()}
            placeholder="用户 ID"
          />
          <Input
            className="h-10 w-[200px] border-slate-200 text-sm focus-visible:border-slate-200 focus-visible:ring-0 focus-visible:ring-offset-0"
            value={filters.toolName}
            onChange={(event) => setFilters((prev) => ({ ...prev, toolName: event.target.value }))}
            onKeyDown={(event) => event.key === "Enter" && handleSearch()}
            placeholder="工具名（如 search_knowledge）"
          />
          <Select
            value={filters.decision || ALL_VALUE}
            onValueChange={(value) =>
              setFilters((prev) => ({ ...prev, decision: value === ALL_VALUE ? "" : value }))
            }
          >
            <SelectTrigger
              aria-label="decision 筛选"
              className="h-10 w-[136px] border-slate-200 text-sm focus:ring-0 focus:ring-offset-0 focus-visible:ring-0 focus-visible:ring-offset-0 data-[state=open]:border-slate-200 data-[state=open]:ring-0"
            >
              <SelectValue placeholder="全部 decision" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL_VALUE}>全部 decision</SelectItem>
              {DECISION_OPTIONS.map((item) => (
                <SelectItem key={item.value} value={item.value}>
                  {item.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <div className="flex h-10 items-center rounded-md border border-slate-200 bg-white px-2 transition-colors focus-within:border-slate-300">
            <input
              type="date"
              value={filters.beginTime}
              max={filters.endTime || undefined}
              onChange={(event) => setFilters((prev) => ({ ...prev, beginTime: event.target.value }))}
              className="w-[116px] bg-transparent px-1 text-sm text-slate-600 focus:outline-none"
            />
            <span className="px-0.5 text-slate-300">~</span>
            <input
              type="date"
              value={filters.endTime}
              min={filters.beginTime || undefined}
              onChange={(event) => setFilters((prev) => ({ ...prev, endTime: event.target.value }))}
              className="w-[116px] bg-transparent px-1 text-sm text-slate-600 focus:outline-none"
            />
          </div>
          <div className="flex items-center gap-2">
            <Button className="admin-primary-gradient h-10 px-4" onClick={handleSearch}>
              <Search className="mr-1.5 h-4 w-4" />
              查询
            </Button>
            <Button variant="outline" className="h-10 border-slate-200 px-4" onClick={handleReset}>
              重置
            </Button>
          </div>
        </div>
      </div>

      <Card className="overflow-hidden">
        <CardContent className="pt-4">
          {loading ? (
            <div className="py-10 text-center text-sm text-muted-foreground">加载中...</div>
          ) : records.length === 0 ? (
            <div className="py-10 text-center text-sm text-muted-foreground">
              暂无工具调用审计（审计 flag 默认关，开启后此处逐行累积）
            </div>
          ) : (
            <Table className="min-w-[1180px] [&_th]:h-10 [&_th]:py-2 [&_td]:py-2">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[190px]">工具</TableHead>
                  <TableHead className="w-[110px]">decision</TableHead>
                  <TableHead className="w-[90px]">status</TableHead>
                  <TableHead className="w-[110px]">用户</TableHead>
                  <TableHead className="w-[70px]">角色</TableHead>
                  <TableHead className="w-[120px]">耗时</TableHead>
                  <TableHead className="w-[170px]">入参摘要 (digest 前 12 位)</TableHead>
                  <TableHead className="w-[150px]">时间</TableHead>
                  <TableHead className="w-[150px]">消息/会话</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {records.map((item) => (
                  <TableRow key={item.id} className="group text-[13px] hover:!bg-slate-50">
                    <TableCell>
                      <div className="flex items-center gap-2.5">
                        <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-[#eef2ff] text-[#6366F1]">
                          <Wrench className="h-4 w-4" />
                        </span>
                        <div className="min-w-0">
                          <div className="truncate font-medium text-slate-700" title={item.toolName}>
                            {item.toolName}
                          </div>
                          <div className="truncate font-mono text-[11px] text-slate-400" title={item.runId || ""}>
                            {item.runId ? `run ${item.runId}` : "—"}
                          </div>
                        </div>
                      </div>
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline" className={cn("font-medium", decisionBadgeClass(item.decision))}>
                        {decisionLabel(item.decision)}
                      </Badge>
                    </TableCell>
                    <TableCell className="font-mono text-xs text-slate-600">{item.status}</TableCell>
                    <TableCell className="font-mono text-xs text-slate-600">{item.userId}</TableCell>
                    <TableCell className="text-slate-600">{item.role || "-"}</TableCell>
                    <TableCell className="text-slate-600">
                      {item.durationMs == null ? "-" : `${item.durationMs} ms`}
                    </TableCell>
                    <TableCell>
                      <div className="min-w-0">
                        <div className="truncate font-mono text-[11px] text-slate-500" title={item.inputDigest || ""}>
                          {item.inputDigest ? item.inputDigest.slice(0, 12) : "—"}
                        </div>
                        {item.inputExcerpt ? (
                          <div
                            className="max-w-[170px] truncate font-mono text-[11px] text-slate-400"
                            title={item.inputExcerpt}
                          >
                            {item.inputExcerpt}
                          </div>
                        ) : null}
                      </div>
                    </TableCell>
                    <TableCell>
                      <RelativeTime value={item.createTime} />
                    </TableCell>
                    <TableCell className="font-mono text-[11px] text-slate-500">
                      <div className="truncate" title={item.messageId}>
                        msg {item.messageId}
                      </div>
                      <div className="truncate" title={item.conversationId}>
                        conv {item.conversationId}
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {pageData && total > 0 ? (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-slate-500">
          <span>
            共 {total} 条，显示 {rangeStart}-{rangeEnd}
          </span>
          <div className="flex flex-wrap items-center gap-2">
            <span>每页</span>
            <Select
              value={String(pageSize)}
              onValueChange={(value) => {
                setPageSize(Number(value));
                setPageNo(1);
              }}
            >
              <SelectTrigger className="h-8 w-[92px]">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {PAGE_SIZE_OPTIONS.map((size) => (
                  <SelectItem key={size} value={String(size)}>
                    {size} 条
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Button variant="outline" size="sm" onClick={() => setPageNo(1)} disabled={current <= 1}>
              首页
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPageNo((prev) => Math.max(1, prev - 1))}
              disabled={current <= 1}
            >
              上一页
            </Button>
            <span>
              {current} / {pages}
            </span>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPageNo((prev) => Math.min(pages || 1, prev + 1))}
              disabled={current >= pages}
            >
              下一页
            </Button>
            <Button variant="outline" size="sm" onClick={() => setPageNo(pages || 1)} disabled={current >= pages}>
              末页
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
