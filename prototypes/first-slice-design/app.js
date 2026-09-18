const state = { screen: "technician", mode: "ready", view: "phone", lang: "en" };

const copy = {
  en: {
    back:"←", product:"HILTECH · FIELD", online:"SYNCED", offline:"OFFLINE · 3 QUEUED",
    project:"Bank HQ · Data Center", site:"Rack Room A", area:"Zone 03",
    title:"Install and dress access rack", code:"WO-0042",
    ready:"READY", progress:"IN PROGRESS", blocked:"BLOCKED", submitted:"SUBMITTED", conflict:"CONFLICT", rework:"REWORK REQUIRED",
    readiness:"Readiness", instruction:"Instruction", evidence:"Evidence", local:"Local work is safe",
    start:"START JOB", block:"BLOCK", resume:"RESUME", submit:"SUBMIT FOR REVIEW", viewSubmission:"VIEW SUBMISSION", resolve:"VIEW CURRENT STATE", continue:"CONTINUE REWORK",
    readyItems:[["People","Crew A assigned"],["Access","Site access confirmed"],["Tools","Fluke-03 + toolkit"],["Material","Rack kit reserved"]],
    steps:[["Verify rack position","Match drawing DR-18 · Rev 04."],["Install rack","Level, anchor and torque."],["Dress cabling","Keep bend radius and service loop."],["Evidence","Capture front, rear and label photos."]],
    evidenceItems:[["Front photo","READY"],["Rear photo","READY"],["Label photo","REQUIRED"]],
    blockedTitle:"Work is blocked",
    blockedText:"Site access is unavailable. The blocker is recorded and the Work Order remains IN_PROGRESS / BLOCKED without fake completion.",
    submittedTitle:"Submitted for review",
    submittedText:"Submitted version 18 is now waiting for Supervisor Review. Evidence and instruction revision are locked to this submission.",
    offlineTitle:"Working offline",
    offlineText:"You can continue. Changes and evidence stay on this device until connection returns.",
    conflictTitle:"Your offline work was not applied",
    conflictText:"This Work Order changed on the server while you were offline. Your photos and notes are preserved.",
    localText:"2 photos, 1 note and a completion attempt are still stored locally.",
    authoritative:"Authoritative state",
    authorityText:"WO-0042 was reassigned by PM at version 18. Your bundle was version 16.",
    reworkTitle:"Supervisor requested rework",
    reworkText:"Cable dressing at the lower tray needs correction. Previous evidence stays in history.",
    reviewReason:"Reason: keep power/data separation through the lower bend.",
  },
  ar: {
    back:"→", product:"هيلتك · الميدان", online:"متزامن", offline:"أوفلاين · ٣ في الانتظار",
    project:"المقر الرئيسي للبنك · مركز البيانات", site:"غرفة الراك A", area:"المنطقة 03",
    title:"تركيب وتجهيز راك الشبكة", code:"WO-0042",
    ready:"جاهز", progress:"قيد التنفيذ", blocked:"متوقف بعائق", submitted:"تم الإرسال", conflict:"تعارض", rework:"مطلوب إعادة عمل",
    readiness:"الجاهزية", instruction:"تعليمات التنفيذ", evidence:"الإثباتات", local:"شغلك المحلي محفوظ",
    start:"ابدأ المهمة", block:"إيقاف بسبب عائق", resume:"استئناف", submit:"إرسال للمراجعة", viewSubmission:"عرض الإرسال", resolve:"عرض الحالة الحالية", continue:"كمّل إعادة العمل",
    readyItems:[["الفريق","Crew A مكلّف"],["الدخول","تم تأكيد دخول الموقع"],["الأدوات","Fluke-03 + عدة الفني"],["الخامات","Rack kit محجوز"]],
    steps:[["تأكد من مكان الراك","طابق الرسم DR-18 · Rev 04."],["ركّب الراك","ميز وثبّت واربط."],["رتّب الكابلات","حافظ على الانحناء والـ service loop."],["الإثبات","صورة أمامية وخلفية والليبل."]],
    evidenceItems:[["الصورة الأمامية","جاهزة"],["الصورة الخلفية","جاهزة"],["صورة الليبل","مطلوبة"]],
    blockedTitle:"الشغل متوقف بسبب عائق",
    blockedText:"الدخول للموقع غير متاح. العائق متسجل وأمر الشغل يفضل IN_PROGRESS / BLOCKED من غير نجاح وهمي.",
    submittedTitle:"تم الإرسال للمراجعة",
    submittedText:"Submitted version 18 مستني مراجعة المشرف. الإثباتات وإصدار التعليمات مربوطين بالإرسال ده.",
    offlineTitle:"أنت تعمل بدون إنترنت",
    offlineText:"تقدر تكمل. التغييرات والصور هتفضل على الجهاز لحد ما الاتصال يرجع.",
    conflictTitle:"شغلك الأوفلاين لم يُطبّق",
    conflictText:"أمر الشغل اتغير على السيرفر وإنت أوفلاين. الصور والملاحظات ما اتمسحتش.",
    localText:"صورتان + ملاحظة + محاولة إنهاء محفوظين محليًا.",
    authoritative:"الحالة المعتمدة",
    authorityText:"WO-0042 اتعمل له Reassign بواسطة الـPM عند version 18. النسخة عندك كانت 16.",
    reworkTitle:"المشرف طلب إعادة عمل",
    reworkText:"ترتيب الكابلات عند المسار السفلي محتاج تصحيح. الإثبات القديم محفوظ في الـhistory.",
    reviewReason:"السبب: حافظ على فصل كابلات الطاقة عن الداتا عند الانحناء السفلي.",
  }
};

function esc(s){return String(s)}
function renderTechnician(){
  const t = copy[state.lang];
  const dir = state.lang === "ar" ? "rtl" : "ltr";
  const isReady = state.mode==="ready";
  const isOffline = state.mode==="offline";
  const isBlocked = state.mode==="blocked";
  const isSubmitted = state.mode==="submitted";
  const isConflict = state.mode==="conflict";
  const isRework = state.mode==="rework";

  const statusClass = isReady ? "ready" : isOffline ? "progress" : isBlocked ? "rework" : isSubmitted ? "ready" : isConflict ? "conflict" : "rework";
  const statusText = isReady ? t.ready : isOffline ? t.progress : isBlocked ? t.blocked : isSubmitted ? t.submitted : isConflict ? t.conflict : t.rework;
  const syncClass = isOffline || isConflict ? "sync offline" : "sync";

  let banner = "";
  if(isOffline) banner = `<div class="banner offline"><strong>${t.offlineTitle}</strong><p>${t.offlineText}</p></div>`;
  if(isBlocked) banner = `<div class="banner rework"><strong>${t.blockedTitle}</strong><p>${t.blockedText}</p></div>`;
  if(isSubmitted) banner = `<div class="banner offline" style="background:var(--ok-bg);color:var(--ok)"><strong>${t.submittedTitle}</strong><p>${t.submittedText}</p></div>`;
  if(isConflict) banner = `<div class="banner conflict"><strong>${t.conflictTitle}</strong><p>${t.conflictText}</p></div><div class="local-card"><strong>${t.local}</strong><p>${t.localText}</p></div>`;
  if(isRework) banner = `<div class="banner rework"><strong>${t.reworkTitle}</strong><p>${t.reworkText}</p><p><b>${t.reviewReason}</b></p></div>`;

  const readiness = t.readyItems.map(x=>`<div class="check"><span class="dot">✓</span><div><b>${x[0]}</b><small>${x[1]}</small></div></div>`).join("");
  const steps = t.steps.map((x,i)=>`<div class="step"><div class="step-num">${i+1}</div><div><b>${x[0]}</b><p>${x[1]}</p></div></div>`).join("");
  const evidence = t.evidenceItems.map(x=>`<div class="evidence-row"><span>${x[0]}</span><b class="evidence-state">${x[1]}</b></div>`).join("");

  const conflictAuthority = isConflict ? `
    <section class="section">
      <div class="section-head"><strong>${t.authoritative}</strong><span class="code">v18</span></div>
      <div class="section-body"><p style="margin:0;font-size:12px;line-height:1.5">${t.authorityText}</p></div>
    </section>` : "";

  let actions = "";
  if(isReady) actions = `<button class="primary">${t.start}</button>`;
  if(isOffline) actions = `<button class="secondary">${t.block}</button><button class="primary">${t.submit}</button>`;
  if(isBlocked) actions = `<button class="primary">${t.resume}</button>`;
  if(isSubmitted) actions = `<button class="primary">${t.viewSubmission}</button>`;
  if(isConflict) actions = `<button class="primary">${t.resolve}</button>`;
  if(isRework) actions = `<button class="primary">${t.continue}</button>`;

  document.getElementById("screen").innerHTML = `
    <div class="app" dir="${dir}">
      <header class="topbar">
        <div class="top-left"><button class="icon-btn">${t.back}</button><span class="product-word">${t.product}</span></div>
        <span class="${syncClass}"><i></i>${isOffline||isConflict?t.offline:t.online}</span>
      </header>
      <div class="content">
        <section class="hero">
          ${banner}
          <span class="kicker">${t.project}</span>
          <div class="title-row">
            <div><h1>${t.title}</h1><span class="code">${t.code}</span></div>
            <span class="status ${statusClass}">${statusText}</span>
          </div>
          <div class="context"><span>${t.site}</span><span>${t.area}</span><span>DR-18 · Rev 04</span></div>
        </section>

        <div class="split">
          <div style="display:grid;gap:16px">
            <section class="section">
              <div class="section-head"><strong>${t.readiness}</strong><span class="code">Policy R3</span></div>
              <div class="section-body"><div class="readiness-grid">${readiness}</div></div>
            </section>
            <section class="section">
              <div class="section-head"><strong>${t.instruction}</strong><span class="code">Rev 04</span></div>
              <div class="section-body"><div class="timeline">${steps}</div></div>
            </section>
          </div>
          <div style="display:grid;align-content:start;gap:16px">
            ${conflictAuthority}
            <section class="section">
              <div class="section-head"><strong>${t.evidence}</strong><span class="code">3 items</span></div>
              <div class="section-body">${evidence}</div>
            </section>
          </div>
        </div>
      </div>
      <footer class="actionbar">${actions}</footer>
    </div>`;
}


const warehouseCopy = {
  en: {
    product:"HILTECH · WAREHOUSE", synced:"LIVE INVENTORY", title:"Fluke 289 True-RMS Multimeter",
    assetCode:"AS-0048", serial:"SN · FLK289-88421", location:"Maadi Main Warehouse", slot:"Cabinet B-04",
    available:"AVAILABLE", reserved:"RESERVED · SAME WORK", calibration:"CALIBRATION BLOCKED", collision:"CHECKOUT NOT APPLIED", success:"CHECKED OUT",
    passport:"Asset passport", checkout:"Checkout context", history:"Movement",
    condition:"Condition", conditionValue:"Good", calibrationLabel:"Calibration", calibrationValue:"Valid · 42 days left",
    custodian:"Current custody", custodianValue:"Stored · Maadi", type:"Asset type", typeValue:"Test instrument",
    recipient:"Recipient", recipientValue:"Crew A · Omar", project:"Project", projectValue:"Bank HQ · Data Center",
    work:"Work Order", workValue:"WO-0042", returnLabel:"Expected return", returnValue:"Tomorrow · 17:00",
    action:"CHECK OUT ASSET", reservedAction:"CHECK OUT RESERVED ASSET", blockedAction:"VIEW CALIBRATION", next:"NEXT SCAN",
    reservedTitle:"Reservation matches this Work Order",
    reservedText:"AS-0048 is reserved for WO-0042 / Crew A. Checkout is allowed and will consume the active reservation.",
    calibrationTitle:"Checkout blocked by calibration policy",
    calibrationText:"This asset requires valid calibration for this WorkType. The current calibration is expired. First slice has no generic override.",
    collisionTitle:"Another device checked out this asset first",
    collisionText:"Your screen was based on version 12. Server version 14 says AS-0048 is now with Crew B.",
    localState:"Your request was not applied. Nothing was overwritten.",
    authoritative:"Authoritative custody", authoritativeValue:"Crew B · Factory Site · WO-0191",
    successTitle:"Custody transferred",
    successText:"Movement MVT-00812 recorded. AS-0048 is now assigned to Crew A for WO-0042."
  },
  ar: {
    product:"هيلتك · المخزن", synced:"المخزون مباشر", title:"Fluke 289 True-RMS Multimeter",
    assetCode:"AS-0048", serial:"SN · FLK289-88421", location:"مخزن المعادي الرئيسي", slot:"الدولاب B-04",
    available:"متاح", reserved:"محجوز لنفس أمر الشغل", calibration:"موقوف بسبب المعايرة", collision:"لم يتم التسليم", success:"تم التسليم",
    passport:"بطاقة الأصل", checkout:"سياق التسليم", history:"الحركة",
    condition:"الحالة", conditionValue:"جيدة", calibrationLabel:"المعايرة", calibrationValue:"صالحة · متبقي 42 يوم",
    custodian:"العهدة الحالية", custodianValue:"في المخزن · المعادي", type:"نوع الأصل", typeValue:"جهاز اختبار",
    recipient:"المستلم", recipientValue:"Crew A · عمر", project:"المشروع", projectValue:"المقر الرئيسي للبنك · مركز البيانات",
    work:"أمر الشغل", workValue:"WO-0042", returnLabel:"الرجوع المتوقع", returnValue:"غدًا · 17:00",
    action:"تسليم الأصل", reservedAction:"تسليم الأصل المحجوز", blockedAction:"عرض المعايرة", next:"المسح التالي",
    reservedTitle:"الحجز مطابق لأمر الشغل ده",
    reservedText:"AS-0048 محجوز لـ WO-0042 / Crew A. التسليم مسموح وهيقفل الحجز النشط.",
    calibrationTitle:"التسليم موقوف بسبب سياسة المعايرة",
    calibrationText:"نوع الشغل ده محتاج معايرة سارية. معايرة الجهاز منتهية، ومفيش Override عام في أول Slice.",
    collisionTitle:"جهاز آخر سلّم الأصل قبلك",
    collisionText:"الشاشة عندك كانت على version 12. السيرفر على version 14 وبيقول إن AS-0048 بقى مع Crew B.",
    localState:"طلبك ما اتطبقش، ومفيش أي حالة معتمدة اتكتبت فوق حالة أحدث.",
    authoritative:"العهدة المعتمدة", authoritativeValue:"Crew B · موقع المصنع · WO-0191",
    successTitle:"تم نقل العهدة",
    successText:"تم تسجيل الحركة MVT-00812. الأصل AS-0048 بقى مع Crew A علشان WO-0042."
  }
};

function renderWarehouse(){
  const t=warehouseCopy[state.lang];
  const dir=state.lang==="ar"?"rtl":"ltr";
  const reserved=state.mode==="reserved";
  const blocked=state.mode==="calibration";
  const collision=state.mode==="collision";
  const success=state.mode==="success";
  const statusClass=blocked?"rework":collision?"conflict":"ready";
  const statusText=reserved?t.reserved:blocked?t.calibration:collision?t.collision:success?t.success:t.available;

  let banner="";
  if(reserved) banner='<div class="banner offline" style="background:var(--ok-bg);color:var(--ok)"><strong>'+t.reservedTitle+'</strong><p>'+t.reservedText+'</p></div>';
  if(blocked) banner='<div class="banner rework"><strong>'+t.calibrationTitle+'</strong><p>'+t.calibrationText+'</p></div>';
  if(collision) banner='<div class="banner conflict"><strong>'+t.collisionTitle+'</strong><p>'+t.collisionText+'</p></div><div class="local-card"><strong>'+t.localState+'</strong><p>Cached v12 · Server v14</p></div>';
  if(success) banner='<div class="banner offline" style="background:var(--ok-bg);color:var(--ok)"><strong>'+t.successTitle+'</strong><p>'+t.successText+'</p></div>';

  const currentCustody=collision?t.authoritativeValue:(success?t.recipientValue:t.custodianValue);
  const calibrationValue=blocked?"Expired · action blocked":t.calibrationValue;

  document.getElementById("screen").innerHTML =
    '<div class="app" dir="'+dir+'">'+
      '<header class="topbar"><div class="top-left"><button class="icon-btn">⌁</button><span class="product-word">'+t.product+'</span></div><span class="sync"><i></i>'+t.synced+'</span></header>'+
      '<div class="content">'+
        '<section class="hero">'+banner+
          '<span class="kicker">'+t.location+' · '+t.slot+'</span>'+
          '<div class="title-row"><div><h1>'+t.title+'</h1><span class="code">'+t.assetCode+' · '+t.serial+'</span></div><span class="status '+statusClass+'">'+statusText+'</span></div>'+
          '<div class="scan-code"><span>QR</span><b>'+t.assetCode+'</b><small>Tap / scan identity</small></div>'+
        '</section>'+
        '<div class="split">'+
          '<section class="section"><div class="section-head"><strong>'+t.passport+'</strong><span class="code">Asset v14</span></div><div class="section-body"><div class="passport-grid">'+
            metric(t.condition,t.conditionValue)+metric(t.calibrationLabel,calibrationValue)+metric(t.custodian,currentCustody)+metric(t.type,t.typeValue)+
          '</div></div></section>'+
          '<div style="display:grid;align-content:start;gap:16px">'+
            (collision?'<section class="section"><div class="section-head"><strong>'+t.authoritative+'</strong><span class="code">v14</span></div><div class="section-body"><div class="movement"><b>'+t.authoritativeValue+'</b><small>Authoritative server custody</small></div></div></section>':'')+
            '<section class="section"><div class="section-head"><strong>'+t.checkout+'</strong><span class="code">Policy C2</span></div><div class="section-body"><div class="passport-grid">'+
              metric(t.recipient,t.recipientValue)+metric(t.project,t.projectValue)+metric(t.work,t.workValue)+metric(t.returnLabel,t.returnValue)+
            '</div></div></section>'+
          '</div>'+
        '</div>'+
      '</div>'+
      '<footer class="actionbar">'+
        (blocked?'<button class="primary">'+t.blockedAction+'</button>':
         collision?'<button class="primary">'+t.next+'</button>':
         success?'<button class="primary">'+t.next+'</button>':
         reserved?'<button class="primary">'+t.reservedAction+'</button>':
         '<button class="primary">'+t.action+'</button>')+
      '</footer>'+
    '</div>';
}

function metric(label,value){
  return '<div class="metric"><small>'+label+'</small><b>'+value+'</b></div>';
}


const configCopy = {
  en: {
    product:"HILTECH · CONTROL", synced:"CONFIG LIVE", title:"Configuration Center", scope:"HILTECH Organization",
    active:"ACTIVE", draft:"DRAFT", invalid:"INVALID DRAFT", activation:"ACTIVATION REVIEW", historyState:"HISTORY", conflict:"VERSION CONFLICT",
    families:["Work Types","Assignment Policies","Readiness Policies","Evidence Policies","Review Policies","Tracking Policies","Approval Policies","Teams / Roles / Delegations","Warehouses / Site Storage","Asset / Stock Master Data","Code Policies","Project Health Policies","Templates / Checklists"],
    selected:"Work Type · DATA_RACK_INSTALL", revision:"Revision 4", activeRev:"Active revision 3",
    summary:"Install, dress and evidence a network/data rack delivery task.",
    fields:[["Assignment Policy","FIELD_CREW_V2"],["Readiness Policy","SITE_READY_R3"],["Evidence Policy","RACK_EVIDENCE_R4"],["Review Policy","TECH_REVIEW_R2"],["Progress Weight","3.000000"],["Tracking","NAVIGATION_ONLY"]],
    save:"SAVE DRAFT", validate:"VALIDATE", compare:"COMPARE ACTIVE", activate:"ACTIVATE REVISION",
    activationTitle:"Activate revision 4?",
    activationText:"This changes the default configuration for future matching Work Orders only. Existing bound Work Orders keep their historical policy revisions.",
    invalidTitle:"Activation blocked",
    invalidText:"Evidence Policy RACK_EVIDENCE_R4 requires Label Photo but the selected template does not materialize that item.",
    conflictTitle:"Draft was based on an older revision",
    conflictText:"Current active revision is 4. Your draft started from revision 3. Nothing was overwritten.",
    usage:"Usage impact", usageText:"8 future planned Work Orders would use this revision. 19 active/historical Work Orders keep their bound revisions.",
    history:"Revision history"
  },
  ar: {
    product:"هيلتك · التحكم", synced:"الإعدادات مباشرة", title:"مركز الإعدادات", scope:"شركة HILTECH",
    active:"مفعّل", draft:"مسودة", invalid:"مسودة غير صالحة", activation:"مراجعة التفعيل", historyState:"السجل", conflict:"تعارض إصدار",
    families:["أنواع الشغل","سياسات التكليف","سياسات الجاهزية","سياسات الإثبات","سياسات المراجعة","سياسات التتبع","سياسات الموافقات","الفرق / الأدوار / التفويضات","المخازن / تخزين المواقع","الأصول / أصناف المخزون","سياسات الأكواد","صحة المشروع","القوالب / قوائم الفحص"],
    selected:"نوع الشغل · DATA_RACK_INSTALL", revision:"الإصدار 4", activeRev:"الإصدار المفعّل 3",
    summary:"تركيب وتجهيز وتوثيق راك شبكات/داتا.",
    fields:[["سياسة التكليف","FIELD_CREW_V2"],["سياسة الجاهزية","SITE_READY_R3"],["سياسة الإثبات","RACK_EVIDENCE_R4"],["سياسة المراجعة","TECH_REVIEW_R2"],["وزن التقدم","3.000000"],["التتبع","NAVIGATION_ONLY"]],
    save:"حفظ المسودة", validate:"تحقق", compare:"قارن بالمفعّل", activate:"تفعيل الإصدار",
    activationTitle:"تفعيل الإصدار 4؟",
    activationText:"ده هيغير الإعداد الافتراضي لأوامر الشغل المستقبلية المطابقة فقط. أوامر الشغل المربوطة بالفعل هتفضل على إصدارات السياسات التاريخية.",
    invalidTitle:"التفعيل موقوف",
    invalidText:"سياسة الإثبات RACK_EVIDENCE_R4 محتاجة صورة الليبل، لكن القالب المختار مش بيعمل البند ده.",
    conflictTitle:"المسودة مبنية على إصدار أقدم",
    conflictText:"الإصدار المفعّل الحالي هو 4. المسودة بدأت من الإصدار 3. مفيش حاجة اتكتبت فوق الأحدث.",
    usage:"تأثير الاستخدام", usageText:"8 أوامر شغل مستقبلية هتستخدم الإصدار ده. 19 أمر شغل جاري/تاريخي هيفضلوا على الإصدارات المربوطين بيها.",
    history:"سجل الإصدارات"
  }
};

function renderConfig(){
  const t=configCopy[state.lang];
  const dir=state.lang==="ar"?"rtl":"ltr";
  const invalid=state.mode==="invalid";
  const activation=state.mode==="activation";
  const historyMode=state.mode==="history";
  const conflict=state.mode==="conflict";
  const isDraft=state.mode==="draft" || invalid || activation || conflict;
  let banner="";
  if(activation) banner='<div class="banner offline" style="background:var(--ok-bg);color:var(--ok)"><strong>'+t.activationTitle+'</strong><p>'+t.activationText+'</p></div>';
  if(invalid) banner='<div class="banner conflict"><strong>'+t.invalidTitle+'</strong><p>'+t.invalidText+'</p></div>';
  if(conflict) banner='<div class="banner conflict"><strong>'+t.conflictTitle+'</strong><p>'+t.conflictText+'</p></div>';

  const familyHtml=t.families.map((x,i)=>
    '<button class="family-row '+(i===0?'selected':'')+'"><span>'+x+'</span><small>'+(i===0?'rev 4':'active')+'</small></button>'
  ).join("");

  const fields=t.fields.map(x=>
    '<div class="config-field"><small>'+x[0]+'</small><b>'+x[1]+'</b><span>›</span></div>'
  ).join("");

  const statusText=historyMode?t.historyState:isDraft?(activation?t.activation:(invalid?t.invalid:(conflict?t.conflict:t.draft))):t.active;
  const statusClass=invalid||conflict?"conflict":activation?"ready":isDraft?"progress":"ready";

  document.getElementById("screen").innerHTML =
  '<div class="app admin-app" dir="'+dir+'">'+
    '<header class="topbar"><div class="top-left"><button class="icon-btn">⌘</button><span class="product-word">'+t.product+'</span></div><span class="sync"><i></i>'+t.synced+'</span></header>'+
    '<div class="admin-layout">'+
      '<aside class="family-panel"><div class="family-head"><small>'+t.scope+'</small><b>'+t.title+'</b></div>'+familyHtml+'</aside>'+
      '<main class="admin-workspace">'+
        '<section class="hero">'+banner+
          '<span class="kicker">'+t.selected+'</span>'+
          '<div class="title-row"><div><h1>DATA_RACK_INSTALL</h1><span class="code">'+t.revision+' · '+t.activeRev+'</span></div><span class="status '+statusClass+'">'+statusText+'</span></div>'+
          '<p class="workspace-summary">'+t.summary+'</p>'+
        '</section>'+
        (historyMode
          ? '<section class="section"><div class="section-head"><strong>'+t.history+'</strong><span class="code">append-only</span></div><div class="section-body revision-list">'+
              '<div><b>Revision 4</b><span>DRAFT · current</span><small>Omar · Today 13:40</small></div>'+
              '<div><b>Revision 3</b><span>ACTIVE</span><small>Mohamed · 12 Sep</small></div>'+
              '<div><b>Revision 2</b><span>SUPERSEDED</span><small>Ahmed · 03 Sep</small></div>'+
              '<div><b>Revision 1</b><span>SUPERSEDED</span><small>System seed</small></div>'+
            '</div></section>'
          : '<section class="section"><div class="section-head"><strong>Policy bindings</strong><span class="code">schema v1</span></div><div class="section-body config-fields">'+fields+'</div></section>'+
            '<section class="section"><div class="section-head"><strong>'+t.usage+'</strong><span class="code">live</span></div><div class="section-body"><p class="body-copy">'+t.usageText+'</p></div></section>')+
      '</main>'+
      '<aside class="inspector-panel">'+
        '<div class="inspector-card"><small>'+t.history+'</small><b>Rev 4 · '+(isDraft?t.draft:t.active)+'</b><p>Rev 3 · ACTIVE<br>Rev 2 · SUPERSEDED<br>Rev 1 · SUPERSEDED</p></div>'+
        '<div class="inspector-card"><small>Validation</small><b>'+(invalid?'1 blocking issue':'No blocking issue')+'</b><p>'+(invalid?t.invalidText:'Dependencies resolve. Historical bindings stay unchanged.')+'</p></div>'+
      '</aside>'+
    '</div>'+
    '<footer class="actionbar admin-actions">'+
      (historyMode?'<button class="primary">CLONE REVISION 3</button>':
       activation?'<button class="secondary">'+t.compare+'</button><button class="primary">'+t.activate+'</button>':
       isDraft?'<button class="secondary">'+t.save+'</button><button class="secondary">'+t.validate+'</button><button class="secondary">'+t.compare+'</button><button class="primary">'+t.activate+'</button>':
       '<button class="primary">CREATE NEW REVISION</button>')+
    '</footer>'+
  '</div>';
}

const reviewCopy = {
  en:{
    product:"HILTECH · REVIEW", synced:"AUTHORITATIVE", title:"Rack installation · WO-0042", project:"Bank HQ · Data Center · Rack Room A",
    clean:"READY TO REVIEW", missing:"MISSING EVIDENCE", rework:"REWORK DECISION", stale:"STALE VERSION",
    evidence:"Evidence", checks:"Acceptance checks", submit:"Submitted version", decision:"Review decision",
    accept:"ACCEPT WORK", reworkAction:"REQUEST REWORK", reject:"REJECT", refresh:"REFRESH SUBMISSION",
    missingTitle:"Acceptance cannot complete", missingText:"Label photo is required by Evidence Policy R4 and is missing.",
    staleTitle:"This submission changed", staleText:"You opened submitted version 18. Server is now version 19. Refresh before deciding.",
    reworkTitle:"Rework is about to be requested", reworkText:"Reason will become part of Work history and the technician will receive the exact required correction."
  },
  ar:{
    product:"هيلتك · المراجعة", synced:"الحالة المعتمدة", title:"تركيب الراك · WO-0042", project:"المقر الرئيسي للبنك · مركز البيانات · غرفة الراك A",
    clean:"جاهز للمراجعة", missing:"إثبات ناقص", rework:"قرار إعادة عمل", stale:"إصدار قديم",
    evidence:"الإثباتات", checks:"فحوص القبول", submit:"الإصدار المرسل", decision:"قرار المراجعة",
    accept:"قبول الشغل", reworkAction:"طلب إعادة عمل", reject:"رفض", refresh:"تحديث الإرسال",
    missingTitle:"مينفعش نكمل القبول", missingText:"صورة الليبل مطلوبة حسب Evidence Policy R4 ومش موجودة.",
    staleTitle:"الإرسال اتغير", staleText:"إنت فتحت submitted version 18. السيرفر دلوقتي version 19. حدّث قبل القرار.",
    reworkTitle:"هيتم طلب إعادة عمل", reworkText:"السبب هيتسجل في الـWork history والفني هيوصله التصحيح المطلوب بالظبط."
  }
};

function renderReview(){
  const t=reviewCopy[state.lang];
  const dir=state.lang==="ar"?"rtl":"ltr";
  const missing=state.mode==="missing";
  const rework=state.mode==="rework";
  const stale=state.mode==="stale";
  const status=missing?t.missing:rework?t.rework:stale?t.stale:t.clean;
  const statusClass=missing||stale?"conflict":rework?"rework":"ready";
  let banner="";
  if(missing) banner='<div class="banner conflict"><strong>'+t.missingTitle+'</strong><p>'+t.missingText+'</p></div>';
  if(stale) banner='<div class="banner conflict"><strong>'+t.staleTitle+'</strong><p>'+t.staleText+'</p></div>';
  if(rework) banner='<div class="banner rework"><strong>'+t.reworkTitle+'</strong><p>'+t.reworkText+'</p></div>';

  const evidence=[
    ["Front rack photo","READY"],
    ["Rear rack photo","READY"],
    ["Label photo",missing?"MISSING":"READY"],
    ["Cable test result","READY"]
  ].map(x=>'<div class="evidence-card '+(x[1]==="MISSING"?'bad':'')+'"><div><b>'+x[0]+'</b><small>Captured by technician · 10:42</small></div><span>'+x[1]+'</span></div>').join("");

  document.getElementById("screen").innerHTML =
  '<div class="app admin-app" dir="'+dir+'">'+
    '<header class="topbar"><div class="top-left"><button class="icon-btn">✓</button><span class="product-word">'+t.product+'</span></div><span class="sync"><i></i>'+t.synced+'</span></header>'+
    '<div class="content review-content">'+
      '<section class="hero">'+banner+'<span class="kicker">'+t.project+'</span>'+
        '<div class="title-row"><div><h1>'+t.title+'</h1><span class="code">'+t.submit+' · v18 · Instruction Rev 04</span></div><span class="status '+statusClass+'">'+status+'</span></div>'+
      '</section>'+
      '<div class="review-grid">'+
        '<section class="section"><div class="section-head"><strong>'+t.evidence+'</strong><span class="code">Policy R4</span></div><div class="section-body evidence-grid">'+evidence+'</div></section>'+
        '<section class="section"><div class="section-head"><strong>'+t.checks+'</strong><span class="code">Review R2</span></div><div class="section-body">'+
          '<div class="review-check"><span class="dot">✓</span><div><b>Rack aligned and anchored</b><small>Accepted requirement</small></div></div>'+
          '<div class="review-check"><span class="dot">✓</span><div><b>Cable bend radius</b><small>Accepted requirement</small></div></div>'+
          '<div class="review-check '+(missing?'review-bad':'')+'"><span class="dot">'+(missing?'!':'✓')+'</span><div><b>Labeling complete</b><small>'+(missing?'Evidence missing':'Evidence matched')+'</small></div></div>'+
        '</div></section>'+
      '</div>'+
      '<section class="section"><div class="section-head"><strong>'+t.decision+'</strong><span class="code">'+(stale?'blocked':'exact v18')+'</span></div><div class="section-body"><textarea class="review-note" placeholder="Reason / reviewer note">'+(rework?'Keep power/data separation through lower bend.':'')+'</textarea></div></section>'+
    '</div>'+
    '<footer class="actionbar admin-actions">'+
      (stale?'<button class="primary">'+t.refresh+'</button>':
       missing?'<button class="secondary">'+t.reworkAction+'</button><button class="danger-btn">'+t.reject+'</button>':
       '<button class="secondary">'+t.reworkAction+'</button><button class="danger-btn">'+t.reject+'</button><button class="primary">'+t.accept+'</button>')+
    '</footer>'+
  '</div>';
}

const projectCopy={
  en:{
    product:"HILTECH · PROJECTS", title:"Bank HQ · Data Center", code:"PRJ-026", synced:"LIVE PROJECT",
    healthy:"HEALTHY", attention:"ATTENTION", critical:"CRITICAL", hold:"ON HOLD",
    progress:"Accepted progress", waiting:"Waiting on", work:"Work pipeline", resources:"Resource readiness", activity:"Activity",
    signals:{healthy:["No active critical signals"],attention:["2 overdue Work Orders","1 blocked by site access"],critical:["Rack delivery milestone +4 days","Fluke-03 unavailable","3 Work Orders blocked"],hold:["Project lifecycle is ON_HOLD"]},
    pct:{healthy:68,attention:61,critical:54,hold:54}
  },
  ar:{
    product:"هيلتك · المشاريع", title:"المقر الرئيسي للبنك · مركز البيانات", code:"PRJ-026", synced:"المشروع مباشر",
    healthy:"مستقر", attention:"يحتاج انتباه", critical:"حرج", hold:"متوقف",
    progress:"التقدم المقبول", waiting:"منتظر على", work:"خط الشغل", resources:"جاهزية الموارد", activity:"النشاط",
    signals:{healthy:["مفيش إشارات حرجة نشطة"],attention:["2 أمر شغل متأخر","1 متوقف بسبب دخول الموقع"],critical:["Milestone الراك متأخر 4 أيام","Fluke-03 غير متاح","3 أوامر شغل متوقفة"],hold:["حالة المشروع ON_HOLD"]},
    pct:{healthy:68,attention:61,critical:54,hold:54}
  }
};

function renderProject(){
  const t=projectCopy[state.lang];
  const dir=state.lang==="ar"?"rtl":"ltr";
  const mode=state.mode;
  const label=t[mode]||t.healthy;
  const statusClass=mode==="healthy"?"ready":mode==="attention"?"rework":mode==="critical"?"conflict":"progress";
  const pct=t.pct[mode]||68;
  const signals=t.signals[mode]||t.signals.healthy;

  const waitingRows = mode==="healthy"
    ? [["Client access approval","Today · 14:00"],["Rack kit delivery","Tomorrow · 09:00"]]
    : mode==="critical"
      ? [["Client shutdown window","Overdue · 2d"],["Fluke-03 replacement","Blocked"],["Rack kit delivery","Overdue · 1d"]]
      : [["Site access confirmation","Overdue · 6h"],["Rack kit delivery","Tomorrow · 09:00"]];

  document.getElementById("screen").innerHTML=
  '<div class="app admin-app" dir="'+dir+'">'+
    '<header class="topbar"><div class="top-left"><button class="icon-btn">⌂</button><span class="product-word">'+t.product+'</span></div><span class="sync"><i></i>'+t.synced+'</span></header>'+
    '<div class="content project-content">'+
      '<section class="hero"><span class="kicker">Project Command Center</span><div class="title-row"><div><h1>'+t.title+'</h1><span class="code">'+t.code+' · baseline v7</span></div><span class="status '+statusClass+'">'+label+'</span></div></section>'+
      '<div class="project-top-grid">'+
        '<section class="section progress-card"><div class="section-head"><strong>'+t.progress+'</strong><span class="code">accepted only</span></div><div class="section-body"><div class="big-progress"><b>'+pct+'%</b><div><span style="width:'+pct+'%"></span></div><small>Accepted weight 34 / baseline 50</small></div></div></section>'+
        '<section class="section"><div class="section-head"><strong>Health signals</strong><span class="code">explainable</span></div><div class="section-body">'+signals.map(x=>'<div class="signal-row"><span class="'+(mode==="critical"?'signal-danger':mode==="attention"?'signal-warn':'signal-ok')+'"></span><b>'+x+'</b></div>').join("")+'</div></section>'+
      '</div>'+
      '<div class="project-main-grid">'+
        '<section class="section"><div class="section-head"><strong>'+t.waiting+'</strong><span class="code">'+waitingRows.length+' items</span></div><div class="section-body">'+waitingRows.map(x=>'<div class="waiting-row"><div><b>'+x[0]+'</b><small>'+x[1]+'</small></div><span>›</span></div>').join("")+'</div></section>'+
        '<section class="section"><div class="section-head"><strong>'+t.work+'</strong><span class="code">live</span></div><div class="section-body"><div class="pipeline"><div><b>12</b><small>Ready</small></div><div><b>7</b><small>In progress</small></div><div><b>3</b><small>Blocked</small></div><div><b>4</b><small>Review</small></div><div><b>28</b><small>Accepted</small></div></div></div></section>'+
        '<section class="section"><div class="section-head"><strong>'+t.resources+'</strong><span class="code">derived</span></div><div class="section-body"><div class="resource-row"><b>People</b><span class="status ready">READY</span></div><div class="resource-row"><b>Material</b><span class="status '+(mode==="critical"?'conflict':'ready')+'">'+(mode==="critical"?'BLOCKED':'READY')+'</span></div><div class="resource-row"><b>Equipment</b><span class="status '+(mode==="critical"?'conflict':'ready')+'">'+(mode==="critical"?'BLOCKED':'READY')+'</span></div><div class="resource-row"><b>Access</b><span class="status '+(mode==="attention"?'rework':'ready')+'">'+(mode==="attention"?'ATTENTION':'READY')+'</span></div></div></section>'+
      '</div>'+
      '<section class="section"><div class="section-head"><strong>'+t.activity+'</strong><span class="code">audit-backed</span></div><div class="section-body activity-line"><span>10:42 · Technician submitted WO-0042</span><span>10:37 · Asset AS-0048 checked out</span><span>09:58 · PM changed site access blocker</span></div></section>'+
    '</div>'+
  '</div>';
}

const stateSets = {
  technician: [
    ["ready","Ready / Online"],
    ["offline","In progress / Offline"],
    ["blocked","Blocked"],
    ["submitted","Submitted"],
    ["conflict","Conflict"],
    ["rework","Rework"]
  ],
  warehouse: [
    ["available","Available"],
    ["reserved","Reserved same work"],
    ["calibration","Calibration blocked"],
    ["collision","Checkout collision"],
    ["success","Success"]
  ],
  config: [
    ["active","Active revision"],
    ["draft","Draft editor"],
    ["invalid","Invalid draft"],
    ["activation","Activation review"],
    ["conflict","Version conflict"],
    ["history","Superseded history"]
  ],
  review: [
    ["clean","Clean submission"],
    ["missing","Missing evidence"],
    ["rework","Rework decision"],
    ["stale","Stale version"]
  ],
  project: [
    ["healthy","Healthy"],
    ["attention","Attention"],
    ["critical","Critical"],
    ["hold","On hold"]
  ]
};

function renderStateControls(){
  const host=document.getElementById("state-controls");
  host.innerHTML=stateSets[state.screen].map((item,i)=>
    '<button data-runtime-state="'+item[0]+'" class="control '+(state.mode===item[0]?'active':'')+'">'+item[1]+'</button>'
  ).join("");
  host.querySelectorAll("[data-runtime-state]").forEach(btn=>{
    btn.addEventListener("click",()=>{
      host.querySelectorAll("[data-runtime-state]").forEach(x=>x.classList.remove("active"));
      btn.classList.add("active");
      state.mode=btn.dataset.runtimeState;
      render();
    });
  });
}

function render(){
  if(state.screen==="warehouse") renderWarehouse();
  else if(state.screen==="config") renderConfig();
  else if(state.screen==="review") renderReview();
  else if(state.screen==="project") renderProject();
  else renderTechnician();
}

document.querySelectorAll("[data-screen]").forEach(btn=>{
  btn.addEventListener("click",()=>{
    document.querySelectorAll("[data-screen]").forEach(x=>x.classList.remove("active"));
    btn.classList.add("active");
    state.screen=btn.dataset.screen;
    const defaults={technician:"ready",warehouse:"available",config:"active",review:"clean",project:"healthy"};
    state.mode=defaults[state.screen]||"ready";
    if(["config","review","project"].includes(state.screen)){
      state.view="desktop";
      document.getElementById("device").className="device desktop";
      document.querySelectorAll("[data-view]").forEach(x=>x.classList.toggle("active",x.dataset.view==="desktop"));
    }
    renderStateControls();
    render();
  });
});

document.querySelectorAll("[data-view]").forEach(btn=>{
  btn.addEventListener("click",()=>{
    document.querySelectorAll("[data-view]").forEach(x=>x.classList.remove("active"));
    btn.classList.add("active");
    state.view=btn.dataset.view;
    document.getElementById("device").className="device "+state.view;
    render();
  });
});

document.querySelectorAll("[data-lang]").forEach(btn=>{
  btn.addEventListener("click",()=>{
    document.querySelectorAll("[data-lang]").forEach(x=>x.classList.remove("active"));
    btn.classList.add("active");
    state.lang=btn.dataset.lang;
    render();
  });
});

renderStateControls();
render();
