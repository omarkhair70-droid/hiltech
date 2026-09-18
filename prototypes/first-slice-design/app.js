const state = { screen: "technician", mode: "ready", view: "phone", lang: "en" };

const copy = {
  en: {
    back:"←", product:"HILTECH · FIELD", online:"SYNCED", offline:"OFFLINE · 3 QUEUED",
    project:"Bank HQ · Data Center", site:"Rack Room A", area:"Zone 03",
    title:"Install and dress access rack", code:"WO-0042",
    ready:"READY", progress:"IN PROGRESS", conflict:"CONFLICT", rework:"REWORK REQUIRED",
    readiness:"Readiness", instruction:"Instruction", evidence:"Evidence", local:"Local work is safe",
    start:"START JOB", block:"BLOCK", submit:"SUBMIT FOR REVIEW", resolve:"VIEW CURRENT STATE", continue:"CONTINUE REWORK",
    readyItems:[["People","Crew A assigned"],["Access","Site access confirmed"],["Tools","Fluke-03 + toolkit"],["Material","Rack kit reserved"]],
    steps:[["Verify rack position","Match drawing DR-18 · Rev 04."],["Install rack","Level, anchor and torque."],["Dress cabling","Keep bend radius and service loop."],["Evidence","Capture front, rear and label photos."]],
    evidenceItems:[["Front photo","READY"],["Rear photo","READY"],["Label photo","REQUIRED"]],
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
    ready:"جاهز", progress:"قيد التنفيذ", conflict:"تعارض", rework:"مطلوب إعادة عمل",
    readiness:"الجاهزية", instruction:"تعليمات التنفيذ", evidence:"الإثباتات", local:"شغلك المحلي محفوظ",
    start:"ابدأ المهمة", block:"إيقاف بسبب عائق", submit:"إرسال للمراجعة", resolve:"عرض الحالة الحالية", continue:"كمّل إعادة العمل",
    readyItems:[["الفريق","Crew A مكلّف"],["الدخول","تم تأكيد دخول الموقع"],["الأدوات","Fluke-03 + عدة الفني"],["الخامات","Rack kit محجوز"]],
    steps:[["تأكد من مكان الراك","طابق الرسم DR-18 · Rev 04."],["ركّب الراك","ميز وثبّت واربط."],["رتّب الكابلات","حافظ على الانحناء والـ service loop."],["الإثبات","صورة أمامية وخلفية والليبل."]],
    evidenceItems:[["الصورة الأمامية","جاهزة"],["الصورة الخلفية","جاهزة"],["صورة الليبل","مطلوبة"]],
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
  const isConflict = state.mode==="conflict";
  const isRework = state.mode==="rework";

  const statusClass = isReady ? "ready" : isOffline ? "progress" : isConflict ? "conflict" : "rework";
  const statusText = isReady ? t.ready : isOffline ? t.progress : isConflict ? t.conflict : t.rework;
  const syncClass = isOffline || isConflict ? "sync offline" : "sync";

  let banner = "";
  if(isOffline) banner = `<div class="banner offline"><strong>${t.offlineTitle}</strong><p>${t.offlineText}</p></div>`;
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
    available:"AVAILABLE", calibration:"CALIBRATION BLOCKED", collision:"CHECKOUT NOT APPLIED", success:"CHECKED OUT",
    passport:"Asset passport", checkout:"Checkout context", history:"Movement",
    condition:"Condition", conditionValue:"Good", calibrationLabel:"Calibration", calibrationValue:"Valid · 42 days left",
    custodian:"Current custody", custodianValue:"Stored · Maadi", type:"Asset type", typeValue:"Test instrument",
    recipient:"Recipient", recipientValue:"Crew A · Omar", project:"Project", projectValue:"Bank HQ · Data Center",
    work:"Work Order", workValue:"WO-0042", returnLabel:"Expected return", returnValue:"Tomorrow · 17:00",
    action:"CHECK OUT ASSET", blockedAction:"VIEW CALIBRATION", next:"NEXT SCAN",
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
    available:"متاح", calibration:"موقوف بسبب المعايرة", collision:"لم يتم التسليم", success:"تم التسليم",
    passport:"بطاقة الأصل", checkout:"سياق التسليم", history:"الحركة",
    condition:"الحالة", conditionValue:"جيدة", calibrationLabel:"المعايرة", calibrationValue:"صالحة · متبقي 42 يوم",
    custodian:"العهدة الحالية", custodianValue:"في المخزن · المعادي", type:"نوع الأصل", typeValue:"جهاز اختبار",
    recipient:"المستلم", recipientValue:"Crew A · عمر", project:"المشروع", projectValue:"المقر الرئيسي للبنك · مركز البيانات",
    work:"أمر الشغل", workValue:"WO-0042", returnLabel:"الرجوع المتوقع", returnValue:"غدًا · 17:00",
    action:"تسليم الأصل", blockedAction:"عرض المعايرة", next:"المسح التالي",
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
  const blocked=state.mode==="calibration";
  const collision=state.mode==="collision";
  const success=state.mode==="success";
  const statusClass=blocked?"rework":collision?"conflict":success?"ready":"ready";
  const statusText=blocked?t.calibration:collision?t.collision:success?t.success:t.available;

  let banner="";
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
         '<button class="primary">'+t.action+'</button>')+
      '</footer>'+
    '</div>';
}

function metric(label,value){
  return '<div class="metric"><small>'+label+'</small><b>'+value+'</b></div>';
}

const stateSets = {
  technician: [
    ["ready","Ready / Online"],
    ["offline","In progress / Offline"],
    ["conflict","Conflict"],
    ["rework","Rework"]
  ],
  warehouse: [
    ["available","Available"],
    ["calibration","Calibration blocked"],
    ["collision","Checkout collision"],
    ["success","Success"]
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
  else renderTechnician();
}

document.querySelectorAll("[data-screen]").forEach(btn=>{
  btn.addEventListener("click",()=>{
    document.querySelectorAll("[data-screen]").forEach(x=>x.classList.remove("active"));
    btn.classList.add("active");
    state.screen=btn.dataset.screen;
    state.mode=state.screen==="warehouse"?"available":"ready";
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
