/**
 * 设备列表的状态标签。后端 factory_equipment.status 是小写英文（active/idle/scrapped/maintenance/inactive 等），
 * 页面一律显示中文；认不出的值原样显示，不猜。
 */
const STATUS_TEXT: Record<string, string> = {
  RUNNING: '运行中',
  ACTIVE: '运行中',
  ONLINE: '在线',
  IDLE: '空闲',
  STANDBY: '待机',
  INACTIVE: '停用',
  OFFLINE: '离线',
  DISABLED: '已禁用',
  MAINTENANCE: '维护中',
  FAULT: '故障',
  ERROR: '故障',
  STOPPED: '已停止',
  SCRAPPED: '已报废',
};

const STATUS_TAG_TYPE: Record<string, string> = {
  RUNNING: 'success',
  ACTIVE: 'success',
  ONLINE: 'success',
  IDLE: 'info',
  STANDBY: 'info',
  INACTIVE: 'info',
  OFFLINE: 'info',
  DISABLED: 'info',
  MAINTENANCE: 'warning',
  FAULT: 'danger',
  ERROR: 'danger',
  STOPPED: 'warning',
  SCRAPPED: 'info',
};

export function equipmentStatusText(status: string): string {
  return STATUS_TEXT[status?.toUpperCase()] || status;
}

export function equipmentStatusTagType(status: string): string {
  return STATUS_TAG_TYPE[status?.toUpperCase()] || 'info';
}
