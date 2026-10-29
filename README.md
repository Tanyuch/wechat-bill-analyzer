# wechat-bill-analyzer

把微信导出的 `.xlsx` 账单读进来，按字段分组统计收支，输出控制台表格 + CSV。

**零第三方依赖** —— 直接拆 ZIP + SAX 解析 OOXML，不引入 POI / EasyExcel，只要有 JDK 就能跑。

## 特性

- **流式解析**：边读边聚合，内存占用与行数无关。10 万行账单在 `-Xmx64m` 下跑完
- **不静默丢数据**：每行必须落到「表头 / 说明 / 空行 / 有效 / 坏行」某个计数器上，对不上直接抛异常
- **表头自动定位**：扫描到同时含「交易时间」「交易类型」「收/支」「金额」的行才认作表头，不依赖固定行号
- **坏行不中断**：时间 / 金额解析失败只计坏行并留样本，不污染统计
- **金额全程 `BigDecimal`**：解析即 `setScale(2, HALF_UP)`，杜绝浮点尾数
- **自带测试数据**：`--generate-test` 可以造任意行数的账单，别人不需要真实数据就能跑

## 快速开始

### 方式一：Maven

```bash
mvn -q clean package
java -jar target/wechat-bill-analyzer-1.0.0.jar --generate-test 100000
java -jar target/wechat-bill-analyzer-1.0.0.jar out/test-100000.xlsx --detail
```

### 方式二：纯 javac（不需要 Maven）

```bash
javac -encoding UTF-8 -d out src/Bill/*.java
java -cp out Bill.MyBill --generate-test 100000
java -cp out Bill.MyBill out/test-100000.xlsx --detail
```

> 所有相对路径都基于**当前工作目录**。命令行请在项目根目录执行；
> 在 IntelliJ 里运行时，把 Run Configuration 的 Working directory 设为项目根目录。

### 换成真实账单

真实账单放在 `data/` 目录下（该目录已被 `.gitignore` 忽略，不会误提交）。
默认路径就是 `data/bill.xlsx`，放好之后直接跑即可，也可以显式传参：

```bash
java -cp out Bill.MyBill "data/bill.xlsx" --detail
```

## 命令行选项

| 选项 | 说明 |
|---|---|
| `--report <path>` | 汇总 CSV 路径，默认 `out/bill-summary.csv` |
| `--detail [path]` | 输出逐笔明细，默认 `out/bill-detail.csv` |
| `--group <field>` | 分组字段：`type`（默认）/ `counterparty` / `direction` / `month` |
| `--generate-test <n>` | 生成 n 行测试账单到 `out/test-<n>.xlsx` 后退出 |
| `--help` | 显示帮助 |

## 输出说明

- **控制台**：分组统计表 + 首尾抽样 + 行数勾稽 + 耗时
- **`out/bill-summary.csv`**：汇总表，UTF-8 带 BOM，Excel 双击可直接打开
- **`out/bill-detail.csv`**：逐笔明细，**第一列是原始表格行号**，可直接回原表核对

## 统计口径

1. 「收/支」= **收入** → 计入收入合计
2. 「收/支」= **支出** → 计入支出合计
3. 「收/支」= **其它**（如 `/`）→ 只计笔数，不进收支合计
4. 金额统一保留 **2 位小数**，四舍五入
5. 净额 = 收入合计 − 支出合计
6. 分组默认按「交易类型」，可用 `--group` 切换

## 性能实测

生成 100,000 行（4.9 MB）账单后：

```
堆内存   : -Xmx64m
解析+聚合: 1.09 s
行数勾稽 : 扫描 100,019 = 表头 1 + 说明 17 + 空行 1 + 有效 100,000 + 坏行 0   [平]
```

## 自检方法

**① 不崩不 OOM**

```bash
java -Xmx64m -cp out Bill.MyBill out/test-100000.xlsx
```

堆限到 64MB 仍能跑完，说明全程流式（SAX 回调 + 边读边聚合）。

**② 行数勾稽必须相等**

控制台会打印 `扫描 = 表头 + 说明 + 空行 + 有效 + 坏行`，左边等于右边才算「没有静默丢数据」。
对不上程序会直接抛异常，而不是悄悄放过。

**③ 手算能与程序对上**

打开 `out/bill-detail.csv`，随便挑 5 行按上面「统计口径」累加，
再和 `out/bill-summary.csv` 里对应分组对比。每行的「行号」列指向原始 xlsx 的行号。

## 已知限制

- 只读第一个工作表（微信账单只有一个 sheet，够用）
- 单行超过 64 列会被丢弃（账单只有 11 列，暂不触发）
- 性能数据基于 `--generate-test` 生成的 `inlineStr` 文件；真实账单用 `sharedStrings`，
  字符串表会整体驻留内存，超大文件的内存表现需以真实数据实测为准

## 设计要点

- **xlsx 本质是 ZIP + XML**：用 `java.util.zip` 解压，再用 SAX 边读边回调，不把整张表读进内存
- **XXE 防护**：解析器开启 `disallow-doctype-decl`，避免恶意 xlsx 触发外部实体注入
- **表头模糊匹配**：列名用 `contains` 匹配关键片段，微信改列名顺序也不会错位
- **日期双路径**：文本格式用正则解析；Excel 序列号按 1899-12-30 基准换算

## 目录结构

```
src/Bill/
  Bill.java             数据模型（record，含原始行号）
  XlsxReader.java       xlsx 底层读取（ZIP + SAX 流式，内存 O(1)）
  BillParser.java       表头定位 / 字段映射 / 坏行诊断 / 行数勾稽
  Aggregator.java       分组聚合 + 汇总 CSV
  DetailWriter.java     逐笔明细 CSV
  ConsoleReport.java    控制台报表（支持 CJK 宽字符对齐）
  SampleGenerator.java  测试账单生成器
  MyBill.java           入口与命令行参数
```

## 许可

MIT
