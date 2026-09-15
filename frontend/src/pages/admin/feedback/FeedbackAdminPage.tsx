import { useCallback, useEffect, useState } from "react";
import { RefreshCw, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { RelativeTime } from "@/components/RelativeTime";
import { Button } from "@/components/ui/button";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle
} from "@/components/ui/alert-dialog";
import { Card, CardContent } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from "@/components/ui/table";
import {
  getSiteFeedbackPage,
  deleteSiteFeedback,
  updateSiteFeedbackStatus,
  type SiteFeedbackItem
} from "@/services/siteService";
import { getErrorMessage } from "@/utils/error";

const PAGE_SIZE = 10;

const STATUS_OPTIONS = [
  { value: 0, label: "未处理", cls: "bg-amber-50 text-amber-700" },
  { value: 1, label: "已处理", cls: "bg-emerald-50 text-emerald-700" },
  { value: 2, label: "忽略", cls: "bg-zinc-100 text-zinc-500" }
];

/**
 * 站点反馈管理页（doc 25）：分页列表 + 状态过滤/流转 + 删除（照 SampleQuestionPage 解剖）。
 * IP 脱敏展示（后端已掩中段）；admin 面不挂 flag。
 */
export function FeedbackAdminPage() {
  const [records, setRecords] = useState<SiteFeedbackItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState<number | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<SiteFeedbackItem | null>(null);

  const load = useCallback(
    async (current = page, status = statusFilter) => {
      try {
        setLoading(true);
        const data = await getSiteFeedbackPage(current, PAGE_SIZE, status);
        setRecords(data.records ?? []);
        setTotal(data.total ?? 0);
      } catch (error) {
        toast.error(getErrorMessage(error, "加载反馈失败"));
        console.error(error);
      } finally {
        setLoading(false);
      }
    },
    [page, statusFilter]
  );

  useEffect(() => {
    load();
  }, [load]);

  const changeStatusFilter = (status: number | undefined) => {
    setPage(1);
    setStatusFilter(status);
    load(1, status);
  };

  const handleStatus = async (item: SiteFeedbackItem, status: number) => {
    if (item.status === status) return;
    try {
      await updateSiteFeedbackStatus(item.id, status);
      toast.success("状态已更新");
      load();
    } catch (error) {
      toast.error(getErrorMessage(error, "状态更新失败"));
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    const id = deleteTarget.id;
    setDeleteTarget(null);
    try {
      await deleteSiteFeedback(id);
      toast.success("已删除");
      // 删掉当前页最后一条时回退一页
      if (records.length === 1 && page > 1) {
        setPage(page - 1);
        load(page - 1);
      } else {
        load();
      }
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
    }
  };

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold">用户反馈</h1>
          <p className="text-sm text-muted-foreground">
            匿名提交 · 共 {total} 条 · IP 已脱敏
          </p>
        </div>
        <div className="flex items-center gap-2">
          <select
            className="h-9 rounded-md border border-input bg-background px-3 text-sm"
            value={statusFilter === undefined ? "" : String(statusFilter)}
            onChange={(event) =>
              changeStatusFilter(event.target.value === "" ? undefined : Number(event.target.value))
            }
            aria-label="状态过滤"
          >
            <option value="">全部状态</option>
            {STATUS_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <Button variant="outline" size="sm" onClick={() => load()}>
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新
          </Button>
        </div>
      </div>

      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-32">时间</TableHead>
                <TableHead>内容</TableHead>
                <TableHead className="w-36">联系方式</TableHead>
                <TableHead className="w-28">IP</TableHead>
                <TableHead className="w-56">状态</TableHead>
                <TableHead className="w-20">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {loading ? (
                <TableRow>
                  <TableCell colSpan={6} className="py-8 text-center text-muted-foreground">
                    加载中…
                  </TableCell>
                </TableRow>
              ) : records.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6} className="py-8 text-center text-muted-foreground">
                    暂无反馈
                  </TableCell>
                </TableRow>
              ) : (
                records.map((item) => {
                  const status = STATUS_OPTIONS[item.status] ?? STATUS_OPTIONS[0];
                  return (
                    <TableRow key={item.id}>
                      <TableCell className="text-xs text-muted-foreground">
                        {item.createTime ? <RelativeTime value={item.createTime} /> : "-"}
                      </TableCell>
                      <TableCell className="max-w-md whitespace-pre-wrap text-sm">
                        {item.content}
                      </TableCell>
                      <TableCell className="text-xs">
                        {item.contact || <span className="text-muted-foreground">-</span>}
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground">
                        {item.clientIpMasked || "-"}
                      </TableCell>
                      <TableCell>
                        <div className="flex items-center gap-1.5">
                          <span className={`rounded-full px-2 py-0.5 text-xs ${status.cls}`}>
                            {status.label}
                          </span>
                          <select
                            className="h-7 rounded-md border border-input bg-background px-1.5 text-xs"
                            value={item.status}
                            onChange={(event) => handleStatus(item, Number(event.target.value))}
                            aria-label={`反馈 ${item.id} 状态流转`}
                          >
                            {STATUS_OPTIONS.map((option) => (
                              <option key={option.value} value={option.value}>
                                {option.label}
                              </option>
                            ))}
                          </select>
                        </div>
                      </TableCell>
                      <TableCell>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-rose-600 hover:text-rose-700"
                          onClick={() => setDeleteTarget(item)}
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </Button>
                      </TableCell>
                    </TableRow>
                  );
                })
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <div className="flex items-center justify-end gap-2 text-sm">
        <Button variant="outline" size="sm" disabled={page <= 1 || loading} onClick={() => {
          const next = page - 1;
          setPage(next);
          load(next);
        }}>
          上一页
        </Button>
        <span className="text-muted-foreground">
          {page} / {totalPages}
        </span>
        <Button variant="outline" size="sm" disabled={page >= totalPages || loading} onClick={() => {
          const next = page + 1;
          setPage(next);
          load(next);
        }}>
          下一页
        </Button>
      </div>

      <AlertDialog open={Boolean(deleteTarget)} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除该反馈？</AlertDialogTitle>
            <AlertDialogDescription>
              这条反馈将被永久删除，无法恢复。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleDelete}
              className="bg-rose-600 text-white hover:bg-rose-700 focus-visible:ring-rose-600"
            >
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
