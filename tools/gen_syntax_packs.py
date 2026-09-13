# -*- coding: utf-8 -*-
"""生成 shso 外置语法包（Monarch 语法 JSON）到仓库 syntax-packs/ 目录。"""
import json, os, re, zipfile

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "syntax-packs")
os.makedirs(OUT, exist_ok=True)

NUM = "(0[xXbBoO][0-9a-fA-F_]+|[0-9][0-9_]*\\.?[0-9_]*([eE][+-]?[0-9]+)?)"
C_IDENT = "[A-Za-z_][A-Za-z0-9_]*"
C_OPS = "[=+\\-*/%<>!&|^~?:]+"
C_DELIM = "[{}()\\[\\]]"

def words(items):
    return "\\b(" + "|".join(items) + ")\\b"

def build(spec):
    root = [["\\s+", "white"]]
    if spec.get("block"):
        root.append([re.escape(spec["block"][0]), "comment", "@comment"])
    if spec.get("line"):
        root.append([spec["line"] + ".*$", "comment"])
    for pat in spec.get("pre", []):
        root.append(pat)
    for q in (spec.get("strings") or []):
        root.append([q + "(\\\\.|[^" + q + "\\\\])*" + q, "string"])
    if spec.get("char"):
        root.append(["'(\\\\.|[^'\\\\])?'", "string"])
    if spec.get("variable"):
        root.append([spec["variable"], "variable"])
    if spec.get("attribute"):
        root.append([spec["attribute"], "attribute"])
    if spec.get("keyword"):
        root.append([words(spec["keyword"]), "keyword"])
    if spec.get("type"):
        root.append([words(spec["type"]), "type"])
    if spec.get("builtin"):
        root.append([words(spec["builtin"]), "constant"])
    root.append([spec.get("number", NUM), "number"])
    root.append([spec.get("identifier", C_IDENT), "identifier"])
    root.append([spec.get("delimiter", C_DELIM), "delimiter"])
    root.append([spec.get("operator", C_OPS), "operator"])
    for pat in spec.get("extra", []):
        root.append(pat)

    tokenizer = {"root": root}
    if spec.get("block"):
        b0, b1 = spec["block"]
        tokenizer["comment"] = [
            [".*?" + re.escape(b1), "comment", "@pop"],
            [".*", "comment"],
        ]

    gram = {"extensions": spec["exts"]}
    if spec.get("ignoreCase"):
        gram["ignoreCase"] = True
    gram["tokenizer"] = tokenizer
    return gram

HASH_LINE = ["#[^\\[].*$", "comment"]
HASH_ALL = ["#.*$", "comment"]

SPECS = []

def add(**kw):
    SPECS.append(kw)

# ── C ────────────────────────────────────────────────────────────────
add(id="cpp", exts=["cpp","cc","cxx","c++","hpp","hh","hxx","h","h++","ino","tcc"],
    line="//", block=("/*","*/"), strings=['"'], char=True,
    pre=[["^\\s*#\\s*[A-Za-z_]+", "attribute"]],
    keyword="if else for while do switch case default break continue return goto sizeof typedef struct union enum static extern const volatile inline register restrict alignof asm noexcept constexpr decltype static_cast dynamic_cast reinterpret_cast const_cast new delete this try catch throw template typename class public private protected virtual override final friend operator namespace using explicit mutable thread_local concept requires co_await co_yield co_return".split(),
    type="void bool char short int long float double unsigned signed wchar_t char8_t char16_t char32_t int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t size_t ssize_t ptrdiff_t intptr_t uintptr_t auto string wstring vector map set unordered_map unordered_set shared_ptr unique_ptr array deque list pair tuple optional variant any function std".split(),
    builtin="true false nullptr NULL NULLPTR stdin stdout stderr".split())

add(id="c", exts=["c"], line="//", block=("/*","*/"), strings=['"'], char=True,
    pre=[["^\\s*#\\s*[A-Za-z_]+", "attribute"]],
    keyword="if else for while do switch case default break continue return goto sizeof typedef struct union enum static extern const volatile inline register restrict _Alignof _Atomic _Generic _Noreturn _Static_assert _Thread_local".split(),
    type="void _Bool char short int long float double unsigned signed wchar_t int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t size_t ssize_t ptrdiff_t intptr_t uintptr_t FILE DIR time_t".split(),
    builtin="true false NULL stdin stdout stderr EXIT_SUCCESS EXIT_FAILURE".split())

add(id="csharp", exts=["cs","csx"], line="//", block=("/*","*/"), strings=['"'], char=True,
    pre=[["^\\s*#\\s*[A-Za-z_]+", "attribute"], ["@?\"(\\\\.|[^\"\\\\])*\"", "string"]],
    keyword="abstract as base break case catch checked continue default delegate do else event explicit extern finally fixed for foreach goto if implicit in interface internal is lock namespace new operator out override params private protected public readonly ref return sealed sizeof stackalloc static switch this throw try typeof unchecked unsafe using virtual void volatile while yield var dynamic async await from where select let orderby group join into nameof when record init".split(),
    type="bool byte sbyte char decimal double float int uint long ulong object short ushort string void nint nuint dynamic Task List Dictionary IEnumerable IQueryable StringBuilder".split(),
    builtin="true false null default value".split())

# ── JVM ──────────────────────────────────────────────────────────────
add(id="java", exts=["java","jsp"], line="//", block=("/*","*/"), strings=['"'], char=True,
    attribute="@[A-Za-z_][A-Za-z0-9_.]*",
    keyword="abstract assert break case catch class const continue default do else enum extends final finally for goto if implements import instanceof interface native new package private protected public return static strictfp super switch synchronized this throw throws transient try volatile while record sealed permits yields var".split(),
    type="void boolean byte char short int long float double String Object Integer Long Double Float Boolean Character List ArrayList Map HashMap Set HashSet Optional Stream StringBuilder Iterable".split(),
    builtin="true false null".split())

add(id="kotlin", exts=["kt","kts"], line="//", block=("/*","*/"), strings=['"'], char=True,
    attribute="@[A-Za-z_][A-Za-z0-9_.]*",
    keyword="as as? break class continue do else false for fun if in !in interface is !is null object package return super this throw true try typealias typeof val var when while by catch finally import init constructor companion data enum sealed inner internal private protected public open override abstract final lateinit suspend inline reified crossinline noinline expect actual".split(),
    type="Byte Short Int Long Float Double Char Boolean String Unit Any Nothing List MutableList Map MutableMap Set MutableSet Array IntArray LongArray Sequence Iterable Comparator Result".split(),
    builtin="true false null Unit".split())

add(id="scala", exts=["scala","sbt","sc"], line="//", block=("/*","*/"), strings=['"'], char=True,
    attribute="@[A-Za-z_][A-Za-z0-9_.]*",
    keyword="abstract case catch class def do else extends false final finally for forSome if implicit import lazy match new null object override package private protected requires return sealed super this throw trait try true type val var while with yield".split(),
    type="Any AnyRef Boolean Byte Char Double Float Int Long Short String Unit Nothing Option Some None List Seq Map Set Future Either Try".split(),
    builtin="true false null None".split())

# ── 脚本 ─────────────────────────────────────────────────────────────
add(id="python", exts=["py","pyi","pyw","pyx"], line="#", strings=['"""', "'''", '"', "'"],
    pre=[["[rRbBuUfF]{0,2}\"\"\"[\\s\\S]*?\"\"\"", "string"], ["[rRbBuUfF]{0,2}'''[\\s\\S]*?'''", "string"],
         ["[rRbBuUfF]{0,2}\"(\\\\.|[^\"\\\\])*\"", "string"], ["[rRbBuUfF]{0,2}'(\\\\.|[^'\\\\])*'", "string"]],
    attribute="@[A-Za-z_][A-Za-z0-9_.]*",
    keyword="and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield match case".split(),
    type="int float str bool bytes list dict set tuple frozenset complex type object".split(),
    builtin="True False None self cls print len range enumerate zip map filter open super isinstance".split())

add(id="shell", exts=["sh","bash","zsh","ksh","fish"], line="#", strings=['"', "'"],
    variable="\\$(\\{[A-Za-z_][A-Za-z0-9_]*\\}|[A-Za-z_][A-Za-z0-9_]*|[0-9@*#?!$])",
    keyword="if then else elif fi for while until do done case esac function select time return exit break continue local export declare readonly read echo printf set unset shift source trap in".split(),
    builtin="true false".split(),
    extra=[["[|&;><]+", "operator"]])

add(id="batch", exts=["bat","cmd"], ignoreCase=True,
    pre=[["^\\s*(REM|rem)\\s.*$", "comment"], ["^\\s*::.*$", "comment"]],
    strings=['"'], variable="%[A-Za-z_][A-Za-z0-9_]*%|![A-Za-z_][A-Za-z0-9_]*!",
    keyword="if else for in do call goto set setlocal endlocal exit echo off on not exist defined start cd copy move del ren md rd pushd popd shift pause title".split(),
    builtin="true false".split())

add(id="powershell", exts=["ps1","psm1","psd1"], ignoreCase=True, line="#",
    pre=[["<#[\\s\\S]*?#>", "comment"]], strings=['"', "'"],
    variable="\\$[A-Za-z_][A-Za-z0-9_]*", attribute="\\[[A-Za-z_][A-Za-z0-9_.*]*\\]",
    keyword="function param begin process end if else elseif for foreach while do until switch break continue return try catch finally throw exit filter in pipe".split(),
    builtin="true false null".split())

add(id="lua", exts=["lua"], pre=[["--\\[\\[[\\s\\S]*?\\]\\]", "comment"], ["--.*$", "comment"]],
    strings=['"', "'"],
    keyword="and break do else elseif end false for function goto if in local nil not or repeat return then true until while".split(),
    builtin="self _G print pairs ipairs require type tostring tonumber".split())

add(id="perl", exts=["pl","pm","t","pod"], line="#", strings=['"', "'"],
    variable="[$@%][A-Za-z_][A-Za-z0-9_:]*",
    keyword="if elsif else unless while until for foreach do last next redo return my our local sub package use no require given when default say print".split(),
    builtin="undef true false".split())

add(id="ruby", exts=["rb","rake","gemspec","ru"], line="#", strings=['"', "'"],
    pre=[["=begin[\\s\\S]*?=end", "comment"]],
    variable="[@$][A-Za-z_][A-Za-z0-9_]*|@@[A-Za-z_][A-Za-z0-9_]*",
    attribute=":[A-Za-z_][A-Za-z0-9_]*",
    keyword="BEGIN END alias and begin break case class def defined? do else elsif end ensure false for if in module next nil not or redo rescue retry return self super then true undef unless until when while yield require attr_accessor attr_reader attr_writer".split(),
    builtin="true false nil".split())

add(id="php", exts=["php","phtml","php5"], line="//", block=("/*","*/"), strings=['"', "'"],
    variable="\\$[A-Za-z_][A-Za-z0-9_]*", attribute="#\\[[^\\]]*\\]",
    keyword="abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile extends final finally for foreach function global goto if implements include include_once instanceof insteadof interface isset list match namespace new or print private protected public readonly require require_once return static switch throw trait try unset use var while xor yield".split(),
    builtin="true false null TRUE FALSE NULL".split())

# ── Web ──────────────────────────────────────────────────────────────
add(id="javascript", exts=["js","jsx","mjs","cjs"], line="//", block=("/*","*/"), strings=['"', "'", "`"],
    attribute="@[A-Za-z_][A-Za-z0-9_.]*",
    keyword="as async await break case catch class const continue debugger default delete do else export extends finally for from function get if import in instanceof let new of return set static super switch this throw try typeof var void while with yield".split(),
    type="Object Array String Number Boolean Symbol Promise Map Set WeakMap WeakSet Date RegExp Error JSON Math Reflect Proxy Function".split(),
    builtin="true false null undefined NaN Infinity globalThis this window document console require module exports".split())

add(id="typescript", exts=["ts","tsx","mts","cts"], line="//", block=("/*","*/"), strings=['"', "'", "`"],
    attribute="@[A-Za-z_][A-Za-z0-9_.]*",
    keyword="abstract as asserts async await break case catch class const continue declare default delete do else enum export extends finally for from function get if implements import in instanceof interface is keyof let new of private protected public readonly return satisfies set static super switch this throw try type typeof var void while yield namespace module infer".split(),
    type="any unknown never void boolean number string symbol object Object Array Promise Map Set Date RegExp Error Function Record Partial Readonly Pick Omit".split(),
    builtin="true false null undefined NaN Infinity globalThis this console".split())

add(id="json", exts=["json","jsonc","json5","lock","babelrc","eslintrc"], strings=['"'],
    keyword=None, builtin="true false null".split(),
    number=NUM, extra=[["[,:;]", "delimiter"]])

add(id="yaml", exts=["yaml","yml"], line="#", strings=['"', "'"],
    variable="\\$\\{[^}]*\\}",
    keyword="true false null yes no on off".split(),
    extra=[["^\\s*-\\s", "delimiter"], ["^\\s*[A-Za-z_][A-Za-z0-9_-]*(?=:)", "type"]])

add(id="toml", exts=["toml"], line="#", strings=['"', "'"],
    keyword="true false".split(),
    extra=[["^\\[[^\\]]*\\]", "type"], ["^\\s*[A-Za-z_][A-Za-z0-9_.-]*(?=\\s*=)", "variable"]])

add(id="ini", exts=["ini","conf","cfg","properties","reg","desktop"], ignoreCase=True,
    pre=[["^\\s*[;#].*$", "comment"]], strings=['"'],
    extra=[["^\\[[^\\]]*\\]", "type"], ["^\\s*[A-Za-z_][A-Za-z0-9_.-]*(?=\\s*=)", "variable"], ["=", "operator"]])

add(id="xml", exts=["xml","xsd","xsl","xslt","svg","plist","iml","manifest"],
    pre=[["<!--[\\s\\S]*?-->", "comment"], ["<[!?][^>]*>", "attribute"],
         ["</?[A-Za-z_][A-Za-z0-9_.:-]*", "type"]], strings=['"', "'"],
    extra=[[">", "type"]])

add(id="html", exts=["html","htm","xhtml","vue","svelte"],
    pre=[["<!--[\\s\\S]*?-->", "comment"], ["<[!?][^>]*>", "attribute"],
         ["</?[A-Za-z_][A-Za-z0-9_.:-]*", "type"]], strings=['"', "'"],
    extra=[[">", "type"]])

add(id="css", exts=["css"], block=("/*","*/"), strings=['"', "'"],
    attribute="@[A-Za-z_][A-Za-z0-9_-]*",
    keyword="important inherit initial unset none auto".split(),
    extra=[["[.#][A-Za-z_][A-Za-z0-9_-]*", "type"], ["[A-Za-z-]+(?=\\s*:)", "variable"], ["[{}();:,]", "delimiter"]])

add(id="scss", exts=["scss","sass","less"], line="//", block=("/*","*/"), strings=['"', "'"],
    attribute="@[A-Za-z_][A-Za-z0-9_-]*", variable="\\$[A-Za-z_][A-Za-z0-9_-]*",
    keyword="important inherit initial unset none auto include mixin extend if else each for while".split(),
    extra=[["[.#][A-Za-z_][A-Za-z0-9_-]*", "type"], ["[{}();:,]", "delimiter"]])

add(id="markdown", exts=["md","markdown","mdown"], strings=None,
    pre=[["^#{1,6}\\s.*$", "type"], ["^\\s*[-*+]\\s", "delimiter"], ["^\\s*>\\s.*$", "comment"],
         ["```[\\s\\S]*?```", "string"], ["`[^`]*`", "string"], ["\\*\\*[^*]+\\*\\*", "constant"]],
    keyword=None)

# ── 系统 / 后端 ──────────────────────────────────────────────────────
add(id="rust", exts=["rs"], line="//", block=("/*","*/"), strings=['"'], char=True,
    attribute="#!?\\[[^\\]]*\\]",
    keyword="as async await break const continue crate dyn else enum extern false for fn if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while union box".split(),
    type="i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str String Vec Option Result Box Rc Arc HashMap HashSet RefCell Cow".split(),
    builtin="true false None Some Ok Err".split())

add(id="go", exts=["go"], line="//", block=("/*","*/"), strings=['"', "`"], char=True,
    keyword="break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var".split(),
    type="bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string uint uint8 uint16 uint32 uint64 uintptr any".split(),
    builtin="true false nil iota make new len cap append copy delete panic recover print println".split())

add(id="swift", exts=["swift"], line="//", block=("/*","*/"), strings=['"'],
    attribute="@[A-Za-z_][A-Za-z0-9_.]*",
    keyword="as associatedtype break case catch class continue default defer deinit didSet do else enum extension fallthrough for func get guard if import in indirect init inout internal is lazy let mutating nonmutating open operator private protocol public repeat required rethrows return self set static struct subscript super switch throw throws try typealias var weak where while willSet unowned fileprivate".split(),
    type="Any AnyObject Bool Character Double Float Int Int8 Int16 Int32 Int64 UInt UInt8 UInt16 UInt32 UInt64 String Array Dictionary Set Optional Void Data Date URL Result".split(),
    builtin="true false nil self".split())

add(id="objectivec", exts=["m","mm"], line="//", block=("/*","*/"), strings=['"'], char=True,
    pre=[["^\\s*#\\s*[A-Za-z_]+", "attribute"], ["@[A-Za-z_][A-Za-z0-9_]*", "keyword"],
         ["@\"(\\\\.|[^\"\\\\])*\"", "string"]],
    keyword="if else for while do switch case default break continue return goto sizeof typedef struct union enum static extern const volatile inline autoreleasepool self super id nil YES NO".split(),
    type="void BOOL char short int long float double unsigned signed NSInteger NSUInteger NSString NSArray NSDictionary NSNumber NSObject CGFloat BOOL SEL Class IMP id instancetype".split(),
    builtin="true false NULL nil YES NO".split())

add(id="dart", exts=["dart"], line="//", block=("/*","*/"), strings=['"', "'"],
    attribute="@[A-Za-z_][A-Za-z0-9_.]*",
    keyword="abstract as assert async await break case catch class const continue covariant default deferred do dynamic else enum export extends extension external factory false final finally for Function get hide if implements import in interface is late library mixin new null on operator part required rethrow return set show static super switch sync this throw true try typedef var void while with yield".split(),
    type="int double num String bool List Map Set Iterable Future Stream Object dynamic void Never".split(),
    builtin="true false null".split())

add(id="groovy", exts=["groovy","gradle","gvy"], line="//", block=("/*","*/"), strings=['"', "'"],
    attribute="@[A-Za-z_][A-Za-z0-9_.]*",
    keyword="as assert break case catch class const continue def default do else enum extends finally for if implements import in instanceof interface new package property return super switch this throw throws trait try while".split(),
    builtin="true false null".split())

add(id="sql", exts=["sql","ddl","dml"], ignoreCase=True,
    pre=[["--.*$", "comment"], ["/\\*[\\s\\S]*?\\*/", "comment"]], strings=["'"],
    keyword="SELECT FROM WHERE INSERT INTO VALUES UPDATE SET DELETE CREATE TABLE DROP ALTER ADD COLUMN INDEX VIEW TRIGGER REPLACE INTO AND OR NOT NULL IS LIKE IN BETWEEN EXISTS ORDER BY GROUP HAVING LIMIT OFFSET UNION ALL DISTINCT CASE WHEN THEN ELSE END BEGIN COMMIT ROLLBACK TRANSACTION PRIMARY KEY FOREIGN REFERENCES DEFAULT UNIQUE CHECK CONSTRAINT JOIN LEFT RIGHT INNER OUTER FULL CROSS ON USING AS ASC DESC WITH RETURNING IF EXPLAIN ANALYZE VACUUM PRAGMA GRANT REVOKE".split(),
    type="INT INTEGER BIGINT SMALLINT TINYINT DECIMAL NUMERIC FLOAT REAL DOUBLE CHAR VARCHAR TEXT BLOB DATE TIME DATETIME TIMESTAMP BOOLEAN JSON UUID SERIAL".split(),
    builtin="true false null current_date current_timestamp now".split())

add(id="makefile", exts=["mk","mak"], ignoreCase=False,
    names=["makefile","gnumakefile"],
    pre=[["^\\s*#.*$", "comment"]], strings=['"', "'"],
    variable="\\$\\([^)]*\\)|\\$[A-Za-z_][A-Za-z0-9_]*",
    keyword="include ifeq ifneq ifdef ifndef else endif define endef export unexport override vpath".split(),
    extra=[["^[A-Za-z_][A-Za-z0-9_.-]*(?=\\s*:)", "type"]])

add(id="haskell", exts=["hs","lhs"], pre=[["--.*$", "comment"], ["\\{-[\\s\\S]*?-\\}", "comment"]],
    strings=['"'], char=True,
    keyword="case class data default deriving do else foreign if import in infix infixl infixr instance let module newtype of then type where _".split(),
    type="Int Integer Float Double Char Bool String IO Maybe Either List".split(),
    builtin="True False Nothing Just Left Right".split())

add(id="erlang", exts=["erl","hrl"], pre=[["%.*$", "comment"]], strings=['"'], char=True,
    variable="[A-Z][A-Za-z0-9_]*|_",
    attribute="-[A-Za-z_][A-Za-z0-9_]*",
    keyword="after andalso and band begin bnot bor bsl bsr bxor case catch cond div end fun if let of orelse or query receive rem try when xor".split(),
    builtin="true false".split())

add(id="elixir", exts=["ex","exs"], pre=[["#.*$", "comment"]], strings=['"', "'"],
    attribute="@[A-Za-z_][A-Za-z0-9_.]*", variable="[A-Z][A-Za-z0-9_]*",
    keyword="def defp defmodule defmacro defprotocol defimpl do end case cond if else unless fn receive try rescue after raise import alias require use when and or not in".split(),
    builtin="true false nil".split())

add(id="r", exts=["r","rmd"], pre=[["#.*$", "comment"]], strings=['"', "'"],
    keyword="if else for while repeat break next return function TRUE FALSE NULL NA Inf NaN".split(),
    builtin="TRUE FALSE NULL NA NaN Inf".split())

add(id="vb", exts=["vb","vbs","bas"], ignoreCase=True, pre=[["'.*$", "comment"]], strings=['"'],
    keyword="if then else elseif end if for each next do while loop until select case sub function dim as set let call exit byval byref public private dim const new nothing true false and or not xor".split(),
    builtin="True False Nothing".split())

# ── 配置 / DevOps / 其它格式 ──────────────────────────────────────────
add(id="dockerfile", exts=["dockerfile","containerfile"], ignoreCase=True,
    names=["dockerfile","containerfile"],
    pre=[["^\\s*#.*$", "comment"]], strings=['"', "'"],
    keyword="FROM RUN CMD LABEL MAINTAINER EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER WORKDIR ARG ONBUILD STOPSIGNAL HEALTHCHECK SHELL AS".split(),
    builtin="true false".split(),
    extra=[["^\\s*[A-Za-z_][A-Za-z0-9_-]*(?=\\s)", "type"]])

add(id="csv", exts=["csv","tsv"], strings=['"'],
    keyword=None,
    extra=[[",", "delimiter"], ["[0-9]+(\\.[0-9]+)?", "number"]])

add(id="asm", exts=["asm","s","nasm","masm"], ignoreCase=True,
    pre=[[";.*$", "comment"], ["#.*$", "comment"]], strings=['"', "'"],
    attribute="\\.[A-Za-z_][A-Za-z0-9_]*",
    keyword="mov lea push pop call ret jmp je jne jz jnz jg jl jge jle ja jb cmp test add sub mul div inc dec and or xor not shl shr nop hlt int syscall section global extern db dw dd dq equ times".split(),
    type="rax rbx rcx rdx rsi rdi rbp rsp r8 r9 r10 r11 r12 r13 r14 r15 eax ebx ecx edx esi edi ebp esp ax bx cx dx al bl cl dl".split())

add(id="protobuf", exts=["proto"], line="//", block=("/*","*/"), strings=['"', "'"],
    keyword="syntax package import option message enum service rpc returns stream repeated optional required reserved extensions extend oneof map public".split(),
    type="double float int32 int64 uint32 uint64 sint32 sint64 fixed32 fixed64 sfixed32 sfixed64 bool string bytes".split(),
    builtin="true false".split())

add(id="terraform", exts=["tf","tfvars","hcl","tfstate"],
    pre=[["#.*$", "comment"], ["/\\*[\\s\\S]*?\\*/", "comment"]], strings=['"'],
    variable="\\$\\{[^}]*\\}",
    keyword="resource variable output module provider data terraform locals backend true false null for_each count depends_on lifecycle if for in".split(),
    extra=[["^\\s*[A-Za-z_][A-Za-z0-9_-]*(?=\\s*=\\s*\\{)", "type"]])

add(id="nginx", exts=["nginx","nginxconf"],
    pre=[["#.*$", "comment"]], strings=['"', "'"],
    variable="\\$[A-Za-z_][A-Za-z0-9_]*",
    keyword="server location listen server_name root index proxy_pass include upstream rewrite return try_files if set client_max_body_size access_log error_log gzip ssl_certificate ssl_certificate_key".split())

add(id="systemd", exts=["service","socket","timer","mount","target","unit","slice","scope","path","swap"],
    pre=[["^\\s*[;#].*$", "comment"]], strings=['"'],
    keyword="Description After Before Requires Wants Type ExecStart ExecStop ExecReload Restart User Group WorkingDirectory Environment EnvironmentFile RemainAfterExit KillMode LimitNOFILE".split(),
    extra=[["^\\[[^\\]]*\\]", "type"]])

add(id="gitconfig", exts=["gitconfig","gitattributes","gitignore","gitmodules"],
    names=["gitignore","gitattributes","gitmodules","gitconfig"],
    pre=[["#.*$", "comment"]], strings=['"'],
    keyword="core user remote branch alias push pull merge rebase diff color status fetch filter include if hasconfig".split(),
    extra=[["^\\[[^\\]]*\\]", "type"], ["^\\s*[-+!*/]", "operator"]])

add(id="diff", exts=["diff","patch"],
    pre=[["^@@[^@]*@@", "type"], ["^diff .*$", "attribute"], ["^index .*$", "comment"],
         ["^\\+.*$", "string"], ["^-.*$", "constant"], ["^--- .*$", "keyword"], ["^\\+\\+\\+ .*$", "keyword"]],
    keyword=None)

add(id="log", exts=["log","out","err"],
    pre=[["^\\s*#.*$", "comment"]], strings=['"', "'"],
    keyword="ERROR WARN WARNING INFO DEBUG TRACE FATAL CRITICAL NOTICE".split(),
    extra=[["\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?", "number"]])

add(id="hosts", exts=["hosts"],
    names=[],
    pre=[["#.*$", "comment"]],
    extra=[["^\\s*\\d+\\.\\d+\\.\\d+\\.\\d+", "number"], ["[A-Za-z0-9_.-]+", "identifier"]])

add(id="crontab", exts=["cron","crontab"],
    names=["crontab"],
    pre=[["#.*$", "comment"]], strings=['"', "'"],
    keyword="MAILTO PATH SHELL HOME".split(),
    extra=[["^@[a-z]+", "keyword"], ["^[0-9@*,/-]+", "number"]])

add(id="regex", exts=["regexp","regex"],
    pre=[["#.*$", "comment"]],
    keyword=None,
    extra=[["\\\\[dDwWsSbBnrtfv0]", "constant"], ["[()\\[\\]{}|?*+^$]", "operator"]])

add(id="latex", exts=["tex","latex","cls","sty","bib","aux"],
    pre=[["%.*$", "comment"]],
    attribute="\\\\[A-Za-z]+",
    extra=[["\\$[^$]*\\$", "string"], ["[{}\\[\\]]", "delimiter"]])

add(id="cmake", exts=["cmake"], ignoreCase=True,
    names=["cmakelists.txt"],
    pre=[["#.*$", "comment"], ["#\\[\\[[\\s\\S]*?\\]\\]", "comment"]], strings=['"'],
    variable="\\$\\{[^}]*\\}",
    keyword="cmake_minimum_required project set add_executable add_library target_link_libraries include_directories find_package if else elseif endif foreach endforeach while endwhile function endfunction macro endmacro option install enable_testing add_test message return break continue".split(),
    builtin="ON OFF TRUE FALSE".split())

add(id="meson", exts=["meson","wrap"],
    names=["meson.build"],
    pre=[["#.*$", "comment"]], strings=["'''", '"', "'"],
    keyword="project executable library static_library shared_library dependency declare_dependency include_directories add_project_arguments subdir if elif else endif foreach endforeach message error warning install_data configure_file".split(),
    builtin="true false".split())

add(id="glsl", exts=["glsl","vert","frag","geom","comp","tesc","tese","shader"],
    line="//", block=("/*","*/"), strings=['"'],
    pre=[["^\\s*#\\s*[A-Za-z_]+", "attribute"]],
    keyword="attribute uniform varying in out inout layout location precision lowp mediump highp const void if else for while do break continue return discard struct".split(),
    type="float int bool vec2 vec3 vec4 ivec2 ivec3 ivec4 bvec2 bvec3 bvec4 mat2 mat3 mat4 sampler2D samplerCube void".split(),
    builtin="true false".split())

add(id="hlsl", exts=["hlsl","hlslinc","fx","fxh","cginc","usf","ush"],
    line="//", block=("/*","*/"), strings=['"'],
    pre=[["^\\s*#\\s*[A-Za-z_]+", "attribute"]],
    keyword="cbuffer register packoffset struct return if else for while do break continue discard inline static const uniform sampler texture".split(),
    type="float float2 float3 float4 half half2 half3 half4 int int2 int3 int4 uint uint2 uint3 uint4 bool bool2 bool3 bool4 matrix float4x4 sampler2D Texture2D SamplerState void".split(),
    builtin="true false".split())

add(id="zig", exts=["zig","zon"], line="//", strings=['"'], char=True,
    attribute="@[A-Za-z_][A-Za-z0-9_]*",
    keyword="const var fn pub usingnamespace return if else switch while for break continue defer errdefer try catch unreachable orelse and or struct enum union error comptime inline export extern asm volatile suspend resume async await test usingnamespace".split(),
    type="void bool u8 u16 u32 u64 u128 usize i8 i16 i32 i64 i128 isize f16 f32 f64 f128 c_char c_int c_long c_uint c_void anyerror anytype type".split(),
    builtin="true false null undefined".split())

add(id="nim", exts=["nim","nims","nimble"], pre=[["#.*$", "comment"]], strings=['"'],
    keyword="proc func method macro template iterator converter var let const type if elif else case of while for try except finally raise defer discard when import include export from as do block object enum tuple ref ptr addr distinct concept static generic bind mixin use".split(),
    type="int int8 int16 int32 int64 uint uint8 uint16 uint32 uint64 float float32 float64 bool char string seq array set auto void".split(),
    builtin="true false nil result self".split())

add(id="julia", exts=["jl"], pre=[["#.*$", "comment"], ["#=[\\s\\S]*?=]#", "comment"]], strings=['"'], char=True,
    attribute="@[A-Za-z_][A-Za-z0-9_.]*",
    keyword="function macro module baremodule using import export struct mutable struct abstract type primitive const global local let if elseif else for while do begin try catch finally return break continue end in where quote begin".split(),
    type="Int Int8 Int16 Int32 Int64 UInt UInt8 UInt16 UInt32 UInt64 Float16 Float32 Float64 Bool Char String Array Vector Matrix Dict Tuple Any Nothing".split(),
    builtin="true false nothing missing self".split())

add(id="matlab", exts=["matlab"], ignoreCase=True,
    pre=[["%.*$", "comment"]], strings=["'", '"'],
    keyword="function end if elseif else for while switch case otherwise try catch break continue return parfor global persistent nargin nargout".split(),
    builtin="true false pi inf nan".split())

add(id="sas", exts=["sas"], ignoreCase=True,
    pre=[["\\*[^;]*;", "comment"], ["/\\*[\\s\\S]*?\\*/", "comment"]], strings=['"', "'"],
    keyword="data proc run quit options libname filename set merge by var input cards datalines output drop keep if then else do end array retain length format informat label attrib where".split(),
    type="numeric character date".split())

add(id="solidity", exts=["sol"], line="//", block=("/*","*/"), strings=['"', "'"],
    keyword="pragma contract interface library function modifier event struct enum mapping constructor returns return if else for while do break continue emit new delete require assert revert payable view pure constant external public private internal override virtual immutable indexed anonymous using is import try catch".split(),
    type="address bool string bytes bytes1 bytes4 bytes8 bytes16 bytes32 uint uint8 uint32 uint64 uint128 uint256 int int8 int32 int64 int128 int256 fixed ufixed".split(),
    builtin="true false msg block tx now this super".split())

def main():
    repo = os.path.dirname(os.path.abspath(__file__))          # tools/
    repo_root = os.path.dirname(repo)
    app_assets = os.path.join(repo_root, "app", "src", "main", "assets", "sora-grammars")
    os.makedirs(OUT, exist_ok=True)

    written, index = [], []
    for spec in SPECS:
        gram = build(spec)                                     # 纯 Monarch
        payload = json.dumps(gram, ensure_ascii=False, indent=2) + "\n"
        with open(os.path.join(OUT, spec["id"] + ".json"), "w", encoding="utf-8", newline="\n") as f:
            f.write(payload)
        index.append({
            "id": spec["id"],
            "grammar": spec["id"] + ".json",
            "exts": spec["exts"],
            "filenames": spec.get("names") or [],
        })
        written.append((spec["id"], spec["exts"], len(payload.encode("utf-8"))))

    # 语法包压缩档（应用整体导入用）：index.json 在根、语法文件在根。
    zip_path = os.path.join(repo_root, "syntax-packs.zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("index.json", json.dumps(index, ensure_ascii=False, indent=2) + "\n")
        for entry in index:
            z.writestr(entry["grammar"], open(os.path.join(OUT, entry["grammar"]), encoding="utf-8").read())

    print(f"生成 {len(written)} 个语法包；压缩档 {zip_path}（{os.path.getsize(zip_path)/1024:.1f} KB）")
    total_ext = sum(len(e) for _, e, _ in written)
    for pid, exts, size in sorted(written):
        print(f"  {pid:<12} {size:>5} B  {','.join(exts)}")
    print(f"覆盖扩展名 {total_ext} 个，语法总体积 {sum(s for _, _, s in written)/1024:.1f} KB")

if __name__ == "__main__":
    main()
