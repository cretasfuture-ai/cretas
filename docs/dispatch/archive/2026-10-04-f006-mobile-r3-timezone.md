任务 F006-MOBILE-R3-TZ-20261004；Base c747cba5e418e72c18032c374e6fffd44fbabfd1。

独立只读定位：固定+08时区午夜瞬间在CI本地日历可能属于前日，而产品formatDate按设备自然日。仅测试固定本地2026-10-04正午，产品源不变。
验收：QualityReportScreenStatistics.test.tsx在TZ=UTC和TZ=America/Los_Angeles顺序运行；两地区各11项通过（总22，F0），git diff --check通过。

源码scope完成并释放，随#892推送；CI完整RN和Java仍须当前HEAD成功后才合入。未部署。
