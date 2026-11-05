# SOP-DOWNLOAD-ENCODING-20261106
Base56a93c20e58f64281c0c584f02be5a0884597bbd；Owner food/root，executor artifact_param_isolation。WT C:/Users/Steve/cretas-food-material-packaging-20261106，branch codex/sop-download-encoding-20261106。只改service/impl/OssServiceImpl.java的URL路径解析及新增service/impl/OssServicePresignedUrlEncodingContractTest.java；本archive由root写。不可改AttachmentController/AttachmentServiceImpl/Supplier/其他组CI或发布脚本/权限或数据库。审计1479当前仅AttachmentController重叠附件范围，未包含OssServiceImpl；本切片为generic OSS签名URL独立Owner，接口不变，将在本组状态通知主协调，不覆盖审计源码。

实际F006对象c5c966dd-f08e-4c25-bbfd-7dc9114e6680/e2705b93-0611-476a-978f-ef75e31cac91，中文PDF真实私有651字节/SHA1a61d145…；下载签名key双编码、实际GET404 NoSuchKey，SDK独立读正确key651/hash匹配且private，proof sop-encoded-download-live-proof.json。原附件保留，修后只读同ID续验，不重复上传。

要求绝对URL路径解码一次、保留literal plus/percent、相对raw key绝不自动解码；合法编码URL去掉query/fragment，兼容原uploadToOss生成的raw空格/Unicode等历史URL不改对象身份，不URLDecoder把+变空格。目标测试调用真实OssServiceImpl.generatePresignedUrl并捕SDK bucket/key，覆盖中文/空格/+/%25/%252F/ASCII/相对raw/legacy raw URL，不只测源码字符串。只跑本scope目标Maven，root最终审diff、官方Java-only构建/stage、CI/normal PR/合并/发布/同对象验收；executor不提交/PR/部署/生产/设备/孙代理。
