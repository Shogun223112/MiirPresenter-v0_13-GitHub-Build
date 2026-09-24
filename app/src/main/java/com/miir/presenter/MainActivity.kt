package com.miir.presenter

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

data class Campaign(val id:String=UUID.randomUUID().toString(),var name:String="Новая кампания")
data class AdventureMeta(val id:String=UUID.randomUUID().toString(),var campaignId:String,var name:String="Новое приключение")
data class Asset(val id:String=UUID.randomUUID().toString(), val uri:String, val type:String)
data class Branch(val label:String,val targetId:String)
data class Scene(val id:String=UUID.randomUUID().toString(),var title:String,var assetUri:String?=null,val branches:MutableList<Branch> = mutableListOf(),var graphX:Float=80f,var graphY:Float=80f,var gmNote:String="")
enum class TokenKind { HERO, ALLY, ENEMY, OBJECT }
data class Token(val id:String=UUID.randomUUID().toString(),var label:String,var x:Float,var y:Float,var visible:Boolean=true,var hp:Int=10,var maxHp:Int=10,var revealsFog:Boolean=false,var visionRadius:Float=110f,var kind:TokenKind=TokenKind.HERO,var imageUri:String?=null,var revealWhenSeen:Boolean=false)
data class FogHole(val x:Float,val y:Float,val radius:Float)
data class MapMarker(val id:String=UUID.randomUUID().toString(),var label:String="Маркер",var x:Float=320f,var y:Float=320f,var visible:Boolean=true,var kind:String="MARKER")
data class BattleMap(val id:String=UUID.randomUUID().toString(),var name:String="Новая карта",var uri:String?=null,val tokens:MutableList<Token> = mutableListOf(),val fogHoles:MutableList<FogHole> = mutableListOf(),val markers:MutableList<MapMarker> = mutableListOf())
data class StatField(var name:String,var modifier:Int)
data class CreatureCard(
    val id:String=UUID.randomUUID().toString(),
    var name:String="Новый враг",
    var hp:Int=10,
    var maxHp:Int=10,
    var tempHp:Int=0,
    var kd:Int=10,
    var imageUri:String?=null,
    val stats:MutableList<StatField> = MutableList(6){ StatField("Характеристика ${it+1}",0) }
)
enum class PlayerMode { SCENE, MAP, BLACK }
enum class FogTool { REVEAL, COVER }

class AppState {
    val campaigns=mutableStateListOf<Campaign>(); val adventures=mutableStateListOf<AdventureMeta>(); var currentCampaignId by mutableStateOf(""); var currentAdventureId by mutableStateOf("")
    var storyText by mutableStateOf(""); var gmNotes by mutableStateOf("")
    val assets=mutableStateListOf<Asset>(); val scenes=mutableStateListOf(Scene(title="Старт")); val tokens=mutableStateListOf(Token(label="Герой",x=250f,y=250f)); val fogHoles=mutableStateListOf<FogHole>(); val creatures=mutableStateListOf<CreatureCard>(); val maps=mutableStateListOf(BattleMap(name="Карта 1"))
    val markers=mutableStateListOf<MapMarker>(); var mapZoom by mutableFloatStateOf(1f); var mapPanX by mutableFloatStateOf(0f); var mapPanY by mutableFloatStateOf(0f); var showTokenLayer by mutableStateOf(true); var showMarkerLayer by mutableStateOf(true); var currentSceneId by mutableStateOf(scenes.first().id); var mapUri by mutableStateOf<String?>(null); var currentMapId by mutableStateOf(maps.first().id); var fogEnabled by mutableStateOf(true); var fogBrush by mutableFloatStateOf(90f); var fogTool by mutableStateOf(FogTool.REVEAL); var playerMode by mutableStateOf(PlayerMode.SCENE); var gridEnabled by mutableStateOf(false); var gridSize by mutableFloatStateOf(72f); var snapToGrid by mutableStateOf(false)
    val currentMap:BattleMap? get()=maps.firstOrNull{it.id==currentMapId}
    fun syncLegacyFromMap(){currentMap?.let{mapUri=it.uri;tokens.clear();tokens.addAll(it.tokens);fogHoles.clear();fogHoles.addAll(it.fogHoles);markers.clear();markers.addAll(it.markers)}}
    fun syncMapFromLegacy(){currentMap?.let{it.uri=mapUri;it.tokens.clear();it.tokens.addAll(tokens);it.fogHoles.clear();it.fogHoles.addAll(fogHoles);it.markers.clear();it.markers.addAll(markers)}}
    val currentScene:Scene? get()=scenes.firstOrNull{it.id==currentSceneId}
}

class MainActivity:ComponentActivity(){
    private val state=AppState(); private var presentation:PlayerPresentation?=null; private var wifi:WifiPlayerServer?=null
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState); ProjectStore.loadWorkspace(this,state); wifi=WifiPlayerServer(this,state).also{it.start()}; setContent{MiirTheme{MasterApp(state,::showExternal,wifi){ProjectStore.saveWorkspace(this,state)}}}; startAutosave()}
    private fun startAutosave(){val h=Handler(Looper.getMainLooper());val r=object:Runnable{override fun run(){ProjectStore.saveWorkspace(this@MainActivity,state);h.postDelayed(this,30_000)}};h.postDelayed(r,30_000)}
    private fun showExternal(){val d=(getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()?:return; presentation?.dismiss(); presentation=PlayerPresentation(this,d,state).also{it.show()}}
    override fun onDestroy(){ProjectStore.saveWorkspace(this,state);wifi?.stop();presentation?.dismiss();super.onDestroy()}
}
class PlayerPresentation(c:Context,d:Display,private val s:AppState):Presentation(c,d){override fun onCreate(b:Bundle?){super.onCreate(b);setContentView(androidx.compose.ui.platform.ComposeView(context).apply{setContent{MaterialTheme{PlayerScreen(s)}}})}}

@Composable fun MiirTheme(content:@Composable ()->Unit){
 val scheme=darkColorScheme(primary=Color(0xFFD0A85C),secondary=Color(0xFF9FB7A0),background=Color(0xFF101214),surface=Color(0xFF181B1E),surfaceVariant=Color(0xFF24282C),onBackground=Color(0xFFE8E2D8),onSurface=Color(0xFFE8E2D8))
 MaterialTheme(colorScheme=scheme,content=content)
}

@Composable fun MasterApp(s:AppState,external:()->Unit,wifi:WifiPlayerServer?,save:()->Unit){
 var tab by remember{mutableIntStateOf(0)};var menu by remember{mutableStateOf(false)};var saved by remember{mutableStateOf(false)}
 Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)){
  TopAppBar(title={Column{Text("Miir Presenter");Text(s.adventures.firstOrNull{it.id==s.currentAdventureId}?.name?:"Выберите приключение",style=MaterialTheme.typography.labelSmall)}},actions={TextButton(onClick={save();saved=true}){Text(if(saved)"Сохранено ✓" else "Сохранить")};Button(onClick=external){Text("Экран игроков")};Box{IconButton({menu=true}){Text("⋮",style=MaterialTheme.typography.headlineSmall)};DropdownMenu(menu,{menu=false}){DropdownMenuItem({Text("Проекты")},{tab=0;menu=false});DropdownMenuItem({Text("Подготовка приключения")},{tab=2;menu=false});DropdownMenuItem({Text("Режим игры")},{tab=1;menu=false})}}})
  val tabs=listOf("Проекты","Игра","Сюжет","Сцены","Материалы","Карты","Существа","Wi‑Fi")
  ScrollableTabRow(tab,edgePadding=4.dp){tabs.forEachIndexed{i,t->Tab(tab==i,{tab=i;saved=false},text={Text(t)})}}
  Surface(Modifier.fillMaxSize()){when(tab){0->WorkspaceScreen(s);1->GameMasterScreen(s);2->StoryScreen(s);3->ScenesScreen(s);4->LibraryScreen(s);5->MapScreen(s,true);6->CreaturesScreen(s);else->WifiScreen(s,wifi)}}
 }
}

@Composable fun WorkspaceScreen(s:AppState){
 val c=LocalContext.current;var campaignName by remember{mutableStateOf("")};var adventureName by remember{mutableStateOf("")};var backupMessage by remember{mutableStateOf("")}
 val activeCampaign=s.campaigns.firstOrNull{it.id==s.currentCampaignId}
 val exportAdventure=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){u->if(u!=null){backupMessage=ProjectStore.exportAdventure(c,s,u)}}
 val exportCampaign=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){u->if(u!=null){backupMessage=ProjectStore.exportCampaign(c,s,u)}}
 val importBackup=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){u->if(u!=null){backupMessage=ProjectStore.importBackup(c,s,u)}}
 Column(Modifier.fillMaxSize().padding(12.dp)){
  Text("Кампании и приключения",style=MaterialTheme.typography.headlineSmall);Text("Каждое приключение хранит собственные сюжет, сцены, карты, существ и библиотеку.",style=MaterialTheme.typography.bodySmall)
  Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton({exportAdventure.launch("приключение.miir.zip")},enabled=s.currentAdventureId.isNotBlank()){Text("Экспорт приключения")};OutlinedButton({exportCampaign.launch("кампания.miir.zip")},enabled=s.currentCampaignId.isNotBlank()){Text("Экспорт кампании")};Button({importBackup.launch(arrayOf("application/zip","application/octet-stream"))}){Text("Импорт")}}
  if(backupMessage.isNotBlank())Text(backupMessage,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
  Row(verticalAlignment=Alignment.CenterVertically){OutlinedTextField(campaignName,{campaignName=it},label={Text("Название кампании")},singleLine=true,modifier=Modifier.weight(1f));Spacer(Modifier.width(6.dp));Button({if(campaignName.isNotBlank()){ProjectStore.saveWorkspace(c,s);val x=Campaign(name=campaignName);s.campaigns+=x;s.currentCampaignId=x.id;campaignName="";ProjectStore.saveIndex(c,s)}}){Text("+ Кампания")}}
  LazyColumn(Modifier.heightIn(max=160.dp)){items(s.campaigns,key={it.id}){x->ListItem({Text(x.name)},{Text(if(x.id==s.currentCampaignId)"Активная кампания" else "Открыть")},modifier=Modifier.clickable{ProjectStore.saveWorkspace(c,s);s.currentCampaignId=x.id;val first=s.adventures.firstOrNull{it.campaignId==x.id};if(first!=null)ProjectStore.switchAdventure(c,s,first.id)})}}
  HorizontalDivider(Modifier.padding(vertical=8.dp));Text(activeCampaign?.name?:"Выберите кампанию",style=MaterialTheme.typography.titleLarge)
  if(activeCampaign!=null){Row(verticalAlignment=Alignment.CenterVertically){OutlinedTextField(adventureName,{adventureName=it},label={Text("Название приключения")},singleLine=true,modifier=Modifier.weight(1f));Spacer(Modifier.width(6.dp));Button({if(adventureName.isNotBlank()){ProjectStore.saveWorkspace(c,s);val a=AdventureMeta(campaignId=activeCampaign.id,name=adventureName);s.adventures+=a;s.currentAdventureId=a.id;ProjectStore.resetAdventure(s);ProjectStore.saveWorkspace(c,s);adventureName=""}}){Text("+ Приключение")}}
   LazyColumn(Modifier.weight(1f)){items(s.adventures.filter{it.campaignId==activeCampaign.id},key={it.id}){a->Card(Modifier.fillMaxWidth().padding(vertical=4.dp).clickable{ProjectStore.switchAdventure(c,s,a.id)},colors=CardDefaults.cardColors(containerColor=if(a.id==s.currentAdventureId)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)){Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(a.name,style=MaterialTheme.typography.titleMedium);Text(if(a.id==s.currentAdventureId)"Открыто сейчас" else "Нажмите, чтобы открыть",style=MaterialTheme.typography.bodySmall)};if(a.id==s.currentAdventureId)Text("●")}}}}
  }
 }
}
@Composable fun GameMasterScreen(s:AppState){
 var battleCreature by remember{mutableStateOf<CreatureCard?>(null)}
 Column(Modifier.fillMaxSize().padding(12.dp)){
  Row(verticalAlignment=Alignment.CenterVertically){
   Column(Modifier.weight(1f)){Text("Стол мастера",style=MaterialTheme.typography.headlineSmall);Text("Режим проведения: только быстрые действия, без перестройки приключения.",style=MaterialTheme.typography.bodySmall)}
   OutlinedButton({s.playerMode=PlayerMode.BLACK}){Text("Скрыть экран")}
  }
  PlayerControls(s)
  HorizontalDivider()
  Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(10.dp)){
   Column(Modifier.weight(1.05f).fillMaxHeight()){
    Text("Текущая сцена",style=MaterialTheme.typography.titleMedium)
    s.currentScene?.let{scene->
     Card(Modifier.fillMaxWidth().padding(vertical=6.dp)){Column(Modifier.padding(10.dp)){
      Text(scene.title,style=MaterialTheme.typography.titleLarge)
      if(scene.gmNote.isNotBlank()) Text(scene.gmNote,style=MaterialTheme.typography.bodyMedium)
      Spacer(Modifier.height(6.dp));Button({s.playerMode=PlayerMode.SCENE}){Text("Показать сцену игрокам")}
     }}
     Text("Переходы",style=MaterialTheme.typography.titleSmall)
     if(scene.branches.isEmpty()) Text("У этой сцены нет переходов",style=MaterialTheme.typography.bodySmall)
     scene.branches.forEach{b->val target=s.scenes.firstOrNull{it.id==b.targetId};OutlinedButton({if(target!=null)s.currentSceneId=target.id},Modifier.fillMaxWidth().padding(vertical=2.dp)){Text("${b.label} → ${target?.title?:"?"}")}}
    }
    HorizontalDivider(Modifier.padding(vertical=8.dp))
    Text("Экран игроков",style=MaterialTheme.typography.titleMedium)
    PlayerPreview(s)
   }
   Column(Modifier.weight(.95f).fillMaxHeight()){
    Row(verticalAlignment=Alignment.CenterVertically){Text("Активная карта",style=MaterialTheme.typography.titleMedium);Spacer(Modifier.weight(1f));TextButton({s.playerMode=PlayerMode.MAP}){Text("Показать")}}
    Text(s.currentMap?.name?:"Карта не выбрана",style=MaterialTheme.typography.bodySmall)
    HorizontalDivider(Modifier.padding(vertical=8.dp))
    Text("Существа",style=MaterialTheme.typography.titleMedium)
    LazyColumn{items(s.creatures,key={it.id}){m->Card(Modifier.fillMaxWidth().padding(vertical=4.dp)){Column(Modifier.padding(8.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text(m.name,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);Text("КД ${m.kd}")};Text("КЗ ${m.hp}/${m.maxHp}"+(if(m.tempHp>0)"  +${m.tempHp} врем." else ""));Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){OutlinedButton({applyDamage(m,1)}){Text("−1")};OutlinedButton({applyDamage(m,5)}){Text("−5")};OutlinedButton({heal(m,5)}){Text("+5")};Button({battleCreature=m}){Text("Бой")}}}}}}
   }
  }
 }
 battleCreature?.let{m->BattleDialog(m){battleCreature=null}}
}

@Composable fun PlayerPreview(s:AppState){
 Card(Modifier.fillMaxWidth().height(180.dp)){Box(Modifier.fillMaxSize().background(Color.Black),contentAlignment=Alignment.Center){when(s.playerMode){PlayerMode.BLACK->Text("Экран скрыт",color=Color.White);PlayerMode.SCENE->s.currentScene?.assetUri?.let{AsyncImage(it,null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}?:Text(s.currentScene?.title?:"Сцена",color=Color.White);PlayerMode.MAP->Column(horizontalAlignment=Alignment.CenterHorizontally){Text("Интерактивная карта",color=Color.White);Text(s.currentMap?.name?:"",color=Color.LightGray)}}}}
}

@Composable fun StoryScreen(s:AppState){
 Column(Modifier.fillMaxSize().padding(12.dp)){
  Text("Приключение мастера",style=MaterialTheme.typography.headlineSmall)
  Text("Этот текст и заметки видны только мастеру. Игрокам отправляются только выбранные сцены и карта.",style=MaterialTheme.typography.bodySmall)
  Spacer(Modifier.height(8.dp))
  OutlinedTextField(s.storyText,{s.storyText=it},label={Text("Сюжет, акты, возможные события")},modifier=Modifier.fillMaxWidth().weight(1f))
  Spacer(Modifier.height(8.dp))
  OutlinedTextField(s.gmNotes,{s.gmNotes=it},label={Text("Наброски и скрытые заметки мастера")},modifier=Modifier.fillMaxWidth().weight(.55f))
 }
}

@Composable fun PlayerControls(s:AppState){Row(Modifier.fillMaxWidth().padding(vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){Button({s.playerMode=PlayerMode.SCENE}){Text("Показать сцену")};Button({s.playerMode=PlayerMode.MAP}){Text("Показать карту")};OutlinedButton({s.playerMode=PlayerMode.BLACK}){Text("Скрыть экран")}}}

@Composable fun ScenesScreen(s:AppState){
 var title by remember{mutableStateOf("")};var branch by remember{mutableStateOf("")};var graphMode by remember{mutableStateOf(true)}
 Column(Modifier.fillMaxSize().padding(12.dp)){
  PlayerControls(s)
  Row(verticalAlignment=Alignment.CenterVertically){
   OutlinedTextField(title,{title=it},label={Text("Новая сцена")},modifier=Modifier.weight(1f),singleLine=true);Spacer(Modifier.width(8.dp))
   Button({if(title.isNotBlank()){val n=s.scenes.size;s.scenes+=Scene(title=title,graphX=60f+(n%3)*250f,graphY=70f+(n/3)*170f);title=""}}){Text("+")}
   Spacer(Modifier.width(8.dp));FilterChip(graphMode,{graphMode=!graphMode},{Text(if(graphMode)"Схема" else "Список")})
  }
  Spacer(Modifier.height(8.dp))
  if(graphMode) SceneGraph(s,Modifier.weight(1f)) else SceneEditorList(s,branch,{branch=it})
  if(graphMode){HorizontalDivider();CurrentSceneEditor(s,branch,{branch=it})}
 }
}

@Composable fun SceneGraph(s:AppState,modifier:Modifier=Modifier){
 Box(modifier.fillMaxWidth().background(Color(0xFF15171A))){
  Canvas(Modifier.fillMaxSize()){
   s.scenes.forEach{from->from.branches.forEach{b->s.scenes.firstOrNull{it.id==b.targetId}?.let{to->
    val a=Offset(from.graphX+90f,from.graphY+42f);val z=Offset(to.graphX+90f,to.graphY+42f)
    drawLine(Color(0xFF9AA0A6),a,z,4f)
    val v=z-a;val len=kotlin.math.sqrt(v.x*v.x+v.y*v.y).coerceAtLeast(1f);val ux=v.x/len;val uy=v.y/len
    val tip=z-Offset(ux*92f,uy*42f);drawLine(Color(0xFF9AA0A6),tip,tip-Offset(ux*16f-uy*9f,uy*16f+ux*9f),4f);drawLine(Color(0xFF9AA0A6),tip,tip-Offset(ux*16f+uy*9f,uy*16f-ux*9f),4f)
   }}}
  }
  s.scenes.forEach{scene->
   val active=scene.id==s.currentSceneId
   Card(Modifier.offset((scene.graphX/3).dp,(scene.graphY/3).dp).width(180.dp).pointerInput(scene.id){detectDragGestures{ch,d->ch.consume();scene.graphX=(scene.graphX+d.x).coerceAtLeast(0f);scene.graphY=(scene.graphY+d.y).coerceAtLeast(0f)}}.clickable{s.currentSceneId=scene.id},colors=CardDefaults.cardColors(containerColor=if(active)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)){
    Column(Modifier.padding(10.dp)){Text(scene.title,style=MaterialTheme.typography.titleSmall);Text("Переходов: ${scene.branches.size}",style=MaterialTheme.typography.bodySmall);if(scene.assetUri!=null)Text("● изображение",style=MaterialTheme.typography.labelSmall)}
   }
  }
 }
}

@Composable fun CurrentSceneEditor(s:AppState,branch:String,setBranch:(String)->Unit){
 s.currentScene?.let{scene->Column(Modifier.fillMaxWidth().padding(top=6.dp)){
  Row(verticalAlignment=Alignment.CenterVertically){Text("Выбрано: ${scene.title}",style=MaterialTheme.typography.titleMedium);Spacer(Modifier.weight(1f));Button({s.playerMode=PlayerMode.SCENE}){Text("Показать игрокам")}}
  OutlinedTextField(scene.gmNote,{scene.gmNote=it},label={Text("Заметка мастера к сцене")},modifier=Modifier.fillMaxWidth(),maxLines=2)
  Row(verticalAlignment=Alignment.CenterVertically){OutlinedTextField(branch,setBranch,label={Text("Подпись перехода")},singleLine=true,modifier=Modifier.weight(1f));Spacer(Modifier.width(6.dp));Text("→")}
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){s.scenes.filter{it.id!=scene.id}.take(5).forEach{target->AssistChip({if(branch.isNotBlank()){scene.branches+=Branch(branch,target.id);setBranch("")}},{Text(target.title.take(14))})}}
  if(scene.branches.isNotEmpty())Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){scene.branches.take(5).forEach{b->AssistChip({scene.branches.remove(b)},{Text("× ${b.label}")})}}
 }}
}

@Composable fun SceneEditorList(s:AppState,branch:String,setBranch:(String)->Unit){
 Column(Modifier.fillMaxSize()){Text("Текущая: ${s.currentScene?.title?:"—"}",style=MaterialTheme.typography.titleMedium);s.currentScene?.assetUri?.let{AsyncImage(it,null,Modifier.fillMaxWidth().height(150.dp),contentScale=ContentScale.Fit)};CurrentSceneEditor(s,branch,setBranch);HorizontalDivider();LazyColumn(Modifier.weight(1f)){items(s.scenes){x->ListItem({Text(x.title)},{Text("Переходов: ${x.branches.size}")},modifier=Modifier.clickable{s.currentSceneId=x.id})}}}
}

@Composable fun LibraryScreen(s:AppState){val c=LocalContext.current;val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->uris.forEach{u->try{c.contentResolver.takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Exception){};s.assets+=Asset(uri=u.toString(),type=c.contentResolver.getType(u)?:"unknown")}};Column(Modifier.fillMaxSize().padding(12.dp)){Button({pick.launch(arrayOf("image/*","video/*","audio/*","text/*","application/pdf"))}){Text("Добавить файлы")};Text("Изображение: нажмите, чтобы назначить текущей сцене.");LazyColumn{items(s.assets){a->ListItem({Text(a.type)},{Text(a.uri.takeLast(48))},leadingContent={if(a.type.startsWith("image"))AsyncImage(a.uri,null,Modifier.size(64.dp))},modifier=Modifier.clickable{if(a.type.startsWith("image"))s.currentScene?.assetUri=a.uri})}}}}

@Composable fun MapScreen(s:AppState,master:Boolean){
 val c=LocalContext.current
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){u:Uri?->u?.let{try{c.contentResolver.takePersistableUriPermission(it,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Exception){};s.mapUri=it.toString();s.currentMap?.uri=s.mapUri}}
 Column(Modifier.fillMaxSize()){
  if(master){
   PlayerControls(s)
   Row(Modifier.padding(8.dp),horizontalArrangement=Arrangement.spacedBy(5.dp)){Button({picker.launch(arrayOf("image/*"))}){Text("Фон")};Button({s.tokens+=Token(label="Ф${s.tokens.size+1}",x=300f,y=300f)}){Text("+ фишка")};Button({s.markers+=MapMarker(label="Маркер ${s.markers.size+1}")}){Text("+ маркер")};FilterChip(s.fogEnabled,{s.fogEnabled=!s.fogEnabled},{Text("Туман")});FilterChip(s.gridEnabled,{s.gridEnabled=!s.gridEnabled},{Text("Сетка")});FilterChip(s.snapToGrid,{s.snapToGrid=!s.snapToGrid},{Text("Привязка")});TextButton({s.fogHoles.clear()}){Text("Закрыть всё")}}
   Row(Modifier.padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){FilterChip(s.fogTool==FogTool.REVEAL,{s.fogTool=FogTool.REVEAL},{Text("Открывать")});FilterChip(s.fogTool==FogTool.COVER,{s.fogTool=FogTool.COVER},{Text("Закрывать")});Text("Кисть");Slider(s.fogBrush,{s.fogBrush=it},valueRange=30f..220f,modifier=Modifier.width(150.dp));if(s.gridEnabled){Text("Клетка");Slider(s.gridSize,{s.gridSize=it},valueRange=36f..144f,modifier=Modifier.width(130.dp))}}
   Row(Modifier.padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){FilterChip(s.showTokenLayer,{s.showTokenLayer=!s.showTokenLayer},{Text("Фишки")});FilterChip(s.showMarkerLayer,{s.showMarkerLayer=!s.showMarkerLayer},{Text("Маркеры")});Text("Масштаб ${String.format("%.1f",s.mapZoom)}×");TextButton({s.mapZoom=1f;s.mapPanX=0f;s.mapPanY=0f}){Text("Сброс вида")}}
   MapSelector(s);TokenVisionControls(s);MarkerControls(s)
  }
  val transformState=rememberTransformableState{zoom,pan,_->s.mapZoom=(s.mapZoom*zoom).coerceIn(.5f,4f);s.mapPanX+=pan.x;s.mapPanY+=pan.y}
  Box(Modifier.fillMaxSize().background(Color.DarkGray).transformable(transformState)){
   Box(Modifier.fillMaxSize().graphicsLayer{scaleX=s.mapZoom;scaleY=s.mapZoom;translationX=s.mapPanX;translationY=s.mapPanY}){
   s.mapUri?.let{AsyncImage(it,null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}
   if(s.gridEnabled)GridLayer(s)
   if(s.showTokenLayer)s.tokens.filter{master||it.visible}.forEach{t->
    Box(Modifier.offset((t.x/3).dp,(t.y/3).dp).size(48.dp).background(if(t.kind==TokenKind.ENEMY) Color(0xFF5A1F1F) else Color.White,CircleShape).pointerInput(t.id,s.snapToGrid,s.gridSize){if(master)detectDragGestures(onDragStart={if(t.revealsFog)revealAroundToken(s,t)},onDragEnd={if(s.snapToGrid){val step=s.gridSize*3f;t.x=(kotlin.math.round(t.x/step)*step);t.y=(kotlin.math.round(t.y/step)*step);if(t.revealsFog)revealAroundToken(s,t)}},onDrag={ch,d->ch.consume();t.x+=d.x*3;t.y+=d.y*3;if(t.revealsFog)revealAroundToken(s,t)})},contentAlignment=Alignment.Center){if(t.imageUri!=null)AsyncImage(t.imageUri,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop) else Text(t.label.take(3),color=if(t.kind==TokenKind.ENEMY)Color.White else Color.Black)}
   }
   if(s.showMarkerLayer)s.markers.filter{master||it.visible}.forEach{m->Box(Modifier.offset((m.x/3).dp,(m.y/3).dp).background(Color(0xCC222222)).padding(horizontal=6.dp,vertical=3.dp).pointerInput(m.id){if(master)detectDragGestures{ch,d->ch.consume();m.x+=d.x*3;m.y+=d.y*3}},contentAlignment=Alignment.Center){Text(if(m.kind=="DOOR")"🚪 ${m.label}" else "◆ ${m.label}",color=Color.White)}}
   if(s.fogEnabled)FogLayer(s,master)
   }
  }
 }
}

@Composable fun GridLayer(s:AppState){Canvas(Modifier.fillMaxSize()){val step=s.gridSize.coerceAtLeast(20f);var x=0f;while(x<size.width){drawLine(Color.White.copy(alpha=.22f),Offset(x,0f),Offset(x,size.height),1f);x+=step};var y=0f;while(y<size.height){drawLine(Color.White.copy(alpha=.22f),Offset(0f,y),Offset(size.width,y),1f);y+=step}}}

@Composable fun FogLayer(s:AppState,master:Boolean){Canvas(Modifier.fillMaxSize().pointerInput(s.fogBrush){if(master)detectDragGestures(onDragStart={p->applyFogBrush(s,p)},onDrag={ch,_->applyFogBrush(s,ch.position)})}){drawIntoCanvas{canvas->val checkpoint=canvas.saveLayer(Rect(0f,0f,size.width,size.height),Paint());drawRect(Color.Black.copy(alpha=.82f));s.fogHoles.forEach{h->drawCircle(Color.Transparent,h.radius,Offset(h.x,h.y),blendMode=BlendMode.Clear)};canvas.restoreToCount(checkpoint)};if(master)s.fogHoles.takeLast(1).forEach{drawCircle(Color.White.copy(alpha=.35f),it.radius,Offset(it.x,it.y),style=androidx.compose.ui.graphics.drawscope.Stroke(2f))}}}


fun revealAroundToken(s:AppState,t:Token){
 if(!s.fogEnabled||!t.revealsFog)return
 val p=Offset(t.x/3f+24f,t.y/3f+24f)
 val last=s.fogHoles.lastOrNull()
 if(last==null || (last.x-p.x)*(last.x-p.x)+(last.y-p.y)*(last.y-p.y) > (t.visionRadius*.35f)*(t.visionRadius*.35f)){
  s.fogHoles+=FogHole(p.x,p.y,t.visionRadius)
  revealHiddenTokens(s,p,t.visionRadius)
  s.currentMap?.let{it.fogHoles.clear();it.fogHoles.addAll(s.fogHoles)}
 }
}

fun revealHiddenTokens(s:AppState,p:Offset,r:Float){s.tokens.filter{!it.visible&&it.revealWhenSeen}.forEach{t->val tx=t.x/3f+24f;val ty=t.y/3f+24f;val dx=tx-p.x;val dy=ty-p.y;if(dx*dx+dy*dy<=r*r)it.visible=true}}

fun applyFogBrush(s:AppState,p:Offset){
 if(s.fogTool==FogTool.REVEAL){s.fogHoles+=FogHole(p.x,p.y,s.fogBrush);revealHiddenTokens(s,p,s.fogBrush)}
 else s.fogHoles.removeAll{h-> val dx=h.x-p.x;val dy=h.y-p.y;dx*dx+dy*dy < (s.fogBrush+h.radius)*(s.fogBrush+h.radius)}
 s.currentMap?.let{it.fogHoles.clear();it.fogHoles.addAll(s.fogHoles)}
}

@Composable fun MapSelector(s:AppState){
 var newName by remember{mutableStateOf("")}
 Column(Modifier.padding(horizontal=8.dp)){
  Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
   Text("Карты:")
   s.maps.take(4).forEach{m->FilterChip(s.currentMapId==m.id,{s.syncMapFromLegacy();s.currentMapId=m.id;s.syncLegacyFromMap()},{Text(m.name)})}
  }
  Row(verticalAlignment=Alignment.CenterVertically){OutlinedTextField(newName,{newName=it},label={Text("Новая карта")},singleLine=true,modifier=Modifier.weight(1f));Spacer(Modifier.width(6.dp));Button({if(newName.isNotBlank()){s.syncMapFromLegacy();val m=BattleMap(name=newName);s.maps+=m;s.currentMapId=m.id;s.mapUri=null;s.tokens.clear();s.fogHoles.clear();s.markers.clear();newName=""}}){Text("+")}}
 }
}


@Composable fun TokenVisionControls(s:AppState){
 if(s.tokens.isEmpty())return
 val c=LocalContext.current;var imageTarget by remember{mutableStateOf<Token?>(null)}
 val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){u:Uri?->u?.let{try{c.contentResolver.takePersistableUriPermission(it,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Exception){};imageTarget?.imageUri=it.toString()}}
 Column(Modifier.padding(horizontal=8.dp,vertical=4.dp)){
  Text("Фишки карты",style=MaterialTheme.typography.labelLarge)
  s.tokens.take(8).forEach{t->Column{Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){Text(t.label,Modifier.width(65.dp));TokenKind.entries.forEach{k->FilterChip(t.kind==k,{t.kind=k},{Text(when(k){TokenKind.HERO->"Герой";TokenKind.ALLY->"Союз";TokenKind.ENEMY->"Враг";TokenKind.OBJECT->"Объект"})})};TextButton({imageTarget=t;pick.launch(arrayOf("image/*"))}){Text("Картинка")};Checkbox(t.visible,{t.visible=it});Text("видим")};Row(verticalAlignment=Alignment.CenterVertically){Checkbox(t.revealsFog,{t.revealsFog=it});Text("Раскрывает туман");Checkbox(t.revealWhenSeen,{t.revealWhenSeen=it});Text("Появится при обнаружении");Text("Обзор");Slider(t.visionRadius,{t.visionRadius=it},valueRange=40f..240f,modifier=Modifier.width(140.dp))}}
 }
}


@Composable fun MarkerControls(s:AppState){
 if(s.markers.isEmpty())return
 Column(Modifier.padding(horizontal=8.dp,vertical=4.dp)){
  Text("Объекты и двери",style=MaterialTheme.typography.labelLarge)
  s.markers.take(8).forEach{m->Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
   OutlinedTextField(m.label,{m.label=it},label={Text("Название")},singleLine=true,modifier=Modifier.width(170.dp))
   FilterChip(m.kind=="MARKER",{m.kind="MARKER"},{Text("Маркер")});FilterChip(m.kind=="DOOR",{m.kind="DOOR"},{Text("Дверь")})
   Checkbox(m.visible,{m.visible=it});Text("игрокам");TextButton({s.markers.remove(m)}){Text("Удалить")}
  }}
 }

@Composable fun CreaturesScreen(s:AppState){
 val c=LocalContext.current; var imageTarget by remember{mutableStateOf<CreatureCard?>(null)}; var combatTarget by remember{mutableStateOf<CreatureCard?>(null)}
 val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){u:Uri?->u?.let{try{c.contentResolver.takePersistableUriPermission(it,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Exception){};imageTarget?.imageUri=it.toString()}}
 combatTarget?.let{m->CombatEditDialog(m){combatTarget=null}}
 Column(Modifier.fillMaxSize().padding(12.dp)){
  Row(verticalAlignment=Alignment.CenterVertically){Text("Существа",style=MaterialTheme.typography.headlineSmall);Spacer(Modifier.weight(1f));Button({s.creatures+=CreatureCard()}){Text("+ карточка")}}
  Text("Карточка Хроник Миира: КЗ, временные КЗ, КД и 6 характеристик. Урон и лечение можно менять прямо во время боя.",style=MaterialTheme.typography.bodySmall)
  LazyColumn{items(s.creatures,key={it.id}){m->Card(Modifier.fillMaxWidth().padding(vertical=6.dp)){Column(Modifier.padding(12.dp)){
   Row(verticalAlignment=Alignment.CenterVertically){OutlinedTextField(m.name,{m.name=it},label={Text("Имя")},modifier=Modifier.weight(1f));Spacer(Modifier.width(8.dp));TextButton({s.creatures.remove(m)}){Text("Удалить")}}
   m.imageUri?.let{AsyncImage(it,null,Modifier.fillMaxWidth().height(160.dp),contentScale=ContentScale.Fit)}
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({imageTarget=m;pick.launch(arrayOf("image/*"))}){Text(if(m.imageUri==null)"Готовая карточка" else "Сменить изображение")};if(m.imageUri!=null)TextButton({m.imageUri=null}){Text("Убрать")}}
   Spacer(Modifier.height(6.dp));Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){Text("КЗ ${m.hp}/${m.maxHp}",style=MaterialTheme.typography.titleMedium);Text("Врем. КЗ ${m.tempHp}");Text("КД ${m.kd}");Spacer(Modifier.weight(1f));Button({combatTarget=m}){Text("Бой")}}
   Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){AssistChip({applyDamage(m,1)},{Text("−1")});AssistChip({applyDamage(m,5)},{Text("−5")});AssistChip({heal(m,1)},{Text("+1")});AssistChip({heal(m,5)},{Text("+5")})}
   m.stats.forEach{st->Row(verticalAlignment=Alignment.CenterVertically){OutlinedTextField(st.name,{st.name=it},label={Text("Характеристика")},modifier=Modifier.weight(1f));Spacer(Modifier.width(8.dp));Text(if(st.modifier>=0)"+${st.modifier}" else st.modifier.toString());TextButton({st.modifier--}){Text("−")};TextButton({st.modifier++}){Text("+")}}}
  }}}}
 }
}

fun applyDamage(m:CreatureCard,amount:Int){var left=amount.coerceAtLeast(0);val absorbed=minOf(m.tempHp,left);m.tempHp-=absorbed;left-=absorbed;m.hp=(m.hp-left).coerceAtLeast(0)}
fun heal(m:CreatureCard,amount:Int){m.hp=(m.hp+amount.coerceAtLeast(0)).coerceAtMost(m.maxHp)}

@Composable fun CombatEditDialog(m:CreatureCard,onClose:()->Unit){
 var amount by remember{mutableStateOf("")};var maxText by remember{mutableStateOf(m.maxHp.toString())};var hpText by remember{mutableStateOf(m.hp.toString())};var tempText by remember{mutableStateOf(m.tempHp.toString())};var kdText by remember{mutableStateOf(m.kd.toString())}
 AlertDialog(onDismissRequest=onClose,title={Text(m.name)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text("Быстрое изменение во время боя")
  OutlinedTextField(amount,{amount=it.filter(Char::isDigit)},label={Text("Количество")},singleLine=true)
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({amount.toIntOrNull()?.let{applyDamage(m,it)};amount=""}){Text("Урон")};Button({amount.toIntOrNull()?.let{heal(m,it)};amount=""}){Text("Лечение")}}
  OutlinedTextField(hpText,{hpText=it.filter(Char::isDigit)},label={Text("Текущие КЗ")},singleLine=true)
  OutlinedTextField(maxText,{maxText=it.filter(Char::isDigit)},label={Text("Максимальные КЗ")},singleLine=true)
  OutlinedTextField(tempText,{tempText=it.filter(Char::isDigit)},label={Text("Временные КЗ")},singleLine=true)
  OutlinedTextField(kdText,{kdText=it.filter(Char::isDigit)},label={Text("КД — класс доспеха")},singleLine=true)
 }},confirmButton={Button({m.maxHp=maxText.toIntOrNull()?.coerceAtLeast(1)?:m.maxHp;m.hp=(hpText.toIntOrNull()?:m.hp).coerceIn(0,m.maxHp);m.tempHp=tempText.toIntOrNull()?.coerceAtLeast(0)?:m.tempHp;m.kd=kdText.toIntOrNull()?.coerceAtLeast(0)?:m.kd;onClose()}){Text("Готово")}},dismissButton={TextButton(onClose){Text("Закрыть")}})
}

@Composable fun TokensScreen(s:AppState){Column(Modifier.fillMaxSize().padding(12.dp)){Text("Фишки и HP",style=MaterialTheme.typography.titleLarge);LazyColumn{items(s.tokens,key={it.id}){t->Card(Modifier.fillMaxWidth().padding(vertical=5.dp)){Column(Modifier.padding(10.dp)){Row(verticalAlignment=Alignment.CenterVertically){OutlinedTextField(t.label,{t.label=it},label={Text("Имя")},modifier=Modifier.weight(1f));Spacer(Modifier.width(8.dp));FilterChip(t.visible,{t.visible=!t.visible},{Text(if(t.visible)"Видим" else "Скрыт")})};Row(verticalAlignment=Alignment.CenterVertically){Text("HP ${t.hp}/${t.maxHp}");Spacer(Modifier.width(8.dp));Button({t.hp=(t.hp-1).coerceAtLeast(0)}){Text("−")};TextButton({t.hp=(t.hp+1).coerceAtMost(t.maxHp)}){Text("+")};Spacer(Modifier.weight(1f));TextButton({s.tokens.remove(t)}){Text("Удалить")}}}}}}}

@Composable fun WifiScreen(s:AppState,server:WifiPlayerServer?){Column(Modifier.fillMaxSize().padding(16.dp)){Text("Wi‑Fi Player",style=MaterialTheme.typography.headlineSmall);Text("Телевизор и Android-устройство должны быть в одной локальной сети. Откройте адрес ниже в браузере телевизора. Экран обновляется автоматически.");Spacer(Modifier.height(12.dp));Text(server?.address?:"Сервер запускается…",style=MaterialTheme.typography.titleLarge);Spacer(Modifier.height(12.dp));PlayerControls(s);Text("Этот режим не зеркалирует экран планшета: браузер получает только разрешённый экран игроков.")}}

@Composable fun PlayerScreen(s:AppState){Box(Modifier.fillMaxSize().background(Color.Black),contentAlignment=Alignment.Center){when(s.playerMode){PlayerMode.BLACK->Unit;PlayerMode.MAP->MapScreen(s,false);PlayerMode.SCENE->s.currentScene?.assetUri?.let{AsyncImage(it,null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}?:Text(s.currentScene?.title?:"Miir Presenter",color=Color.White)}}}

object ProjectStore{
 private const val INDEX="workspace.json"
 private fun file(id:String)="adventure_${id}.json"
 fun saveIndex(c:Context,s:AppState){runCatching{val j=JSONObject().put("currentCampaign",s.currentCampaignId).put("currentAdventure",s.currentAdventureId).put("campaigns",JSONArray().apply{s.campaigns.forEach{put(JSONObject().put("id",it.id).put("name",it.name))}}).put("adventures",JSONArray().apply{s.adventures.forEach{put(JSONObject().put("id",it.id).put("campaignId",it.campaignId).put("name",it.name))}});c.openFileOutput(INDEX,Context.MODE_PRIVATE).bufferedWriter().use{it.write(j.toString())}}}
 fun saveWorkspace(c:Context,s:AppState){if(s.currentAdventureId.isNotBlank())saveAdventure(c,s,file(s.currentAdventureId));saveIndex(c,s)}
 fun loadWorkspace(c:Context,s:AppState){val ok=runCatching{val j=JSONObject(c.openFileInput(INDEX).bufferedReader().readText());s.campaigns.clear();j.optJSONArray("campaigns")?.eachObj{s.campaigns+=Campaign(it.optString("id"),it.optString("name"))};s.adventures.clear();j.optJSONArray("adventures")?.eachObj{s.adventures+=AdventureMeta(it.optString("id"),it.optString("campaignId"),it.optString("name"))};s.currentCampaignId=j.optString("currentCampaign");s.currentAdventureId=j.optString("currentAdventure");if(s.currentAdventureId.isNotBlank())loadAdventure(c,s,file(s.currentAdventureId));true}.getOrDefault(false);if(!ok||s.campaigns.isEmpty()){val cp=Campaign(name="Моя кампания");val ad=AdventureMeta(campaignId=cp.id,name="Приключение 1");s.campaigns+=cp;s.adventures+=ad;s.currentCampaignId=cp.id;s.currentAdventureId=ad.id;runCatching{loadAdventure(c,s,LEGACY_FILE)};saveWorkspace(c,s)}}
 fun switchAdventure(c:Context,s:AppState,id:String){if(id==s.currentAdventureId)return;saveWorkspace(c,s);s.currentAdventureId=id;s.adventures.firstOrNull{it.id==id}?.let{s.currentCampaignId=it.campaignId};resetAdventure(s);loadAdventure(c,s,file(id));saveIndex(c,s)}
 fun resetAdventure(s:AppState){s.storyText="";s.gmNotes="";s.assets.clear();s.scenes.clear();s.scenes+=Scene(title="Старт");s.currentSceneId=s.scenes.first().id;s.creatures.clear();s.maps.clear();s.maps+=BattleMap(name="Карта 1");s.currentMapId=s.maps.first().id;s.syncLegacyFromMap();s.playerMode=PlayerMode.SCENE}
 private const val LEGACY_FILE="project.json"
 private fun saveAdventure(c:Context,s:AppState,file:String){runCatching{val j=JSONObject();j.put("story",s.storyText);j.put("gmNotes",s.gmNotes);j.put("current",s.currentSceneId);j.put("map",s.mapUri);j.put("fog",s.fogEnabled);j.put("mode",s.playerMode.name);j.put("fogTool",s.fogTool.name);j.put("gridEnabled",s.gridEnabled);j.put("gridSize",s.gridSize);j.put("snapToGrid",s.snapToGrid);j.put("mapZoom",s.mapZoom);j.put("mapPanX",s.mapPanX);j.put("mapPanY",s.mapPanY);j.put("showTokenLayer",s.showTokenLayer);j.put("showMarkerLayer",s.showMarkerLayer);s.syncMapFromLegacy();j.put("currentMap",s.currentMapId);j.put("maps",JSONArray().apply{s.maps.forEach{m->put(JSONObject().put("id",m.id).put("name",m.name).put("uri",m.uri).put("tokens",JSONArray().apply{m.tokens.forEach{t->put(JSONObject().put("id",t.id).put("label",t.label).put("x",t.x).put("y",t.y).put("visible",t.visible).put("hp",t.hp).put("maxHp",t.maxHp).put("revealsFog",t.revealsFog).put("visionRadius",t.visionRadius).put("kind",t.kind.name).put("imageUri",t.imageUri).put("revealWhenSeen",t.revealWhenSeen))}}).put("holes",JSONArray().apply{m.fogHoles.forEach{h->put(JSONObject().put("x",h.x).put("y",h.y).put("r",h.radius))}}).put("markers",JSONArray().apply{m.markers.forEach{mk->put(JSONObject().put("id",mk.id).put("label",mk.label).put("x",mk.x).put("y",mk.y).put("visible",mk.visible).put("kind",mk.kind))}}))}});j.put("assets",JSONArray().apply{s.assets.forEach{put(JSONObject().put("id",it.id).put("uri",it.uri).put("type",it.type))}});j.put("scenes",JSONArray().apply{s.scenes.forEach{x->put(JSONObject().put("id",x.id).put("title",x.title).put("asset",x.assetUri).put("graphX",x.graphX).put("graphY",x.graphY).put("gmNote",x.gmNote).put("branches",JSONArray().apply{x.branches.forEach{b->put(JSONObject().put("label",b.label).put("target",b.targetId))}}))}});j.put("tokens",JSONArray().apply{s.tokens.forEach{put(JSONObject().put("id",it.id).put("label",it.label).put("x",it.x).put("y",it.y).put("visible",it.visible).put("hp",it.hp).put("maxHp",it.maxHp).put("revealsFog",it.revealsFog).put("visionRadius",it.visionRadius).put("kind",it.kind.name).put("imageUri",it.imageUri).put("revealWhenSeen",it.revealWhenSeen))}});j.put("holes",JSONArray().apply{s.fogHoles.forEach{put(JSONObject().put("x",it.x).put("y",it.y).put("r",it.radius))}});j.put("creatures",JSONArray().apply{s.creatures.forEach{m->put(JSONObject().put("id",m.id).put("name",m.name).put("hp",m.hp).put("maxHp",m.maxHp).put("tempHp",m.tempHp).put("kd",m.kd).put("image",m.imageUri).put("stats",JSONArray().apply{m.stats.forEach{st->put(JSONObject().put("name",st.name).put("mod",st.modifier))}}))}});c.openFileOutput(file,Context.MODE_PRIVATE).bufferedWriter().use{it.write(j.toString())}}}
 private fun loadAdventure(c:Context,s:AppState,file:String){runCatching{val j=JSONObject(c.openFileInput(file).bufferedReader().readText());s.storyText=j.optString("story","");s.gmNotes=j.optString("gmNotes","");s.assets.clear();j.getJSONArray("assets").eachObj{ s.assets+=Asset(it.getString("id"),it.getString("uri"),it.getString("type"))};s.scenes.clear();j.getJSONArray("scenes").eachObj{o->val bs=mutableListOf<Branch>();o.getJSONArray("branches").eachObj{bs+=Branch(it.getString("label"),it.getString("target"))};s.scenes+=Scene(o.getString("id"),o.getString("title"),o.optString("asset").takeIf{it.isNotBlank()&&it!="null"},bs,o.optDouble("graphX",80.0).toFloat(),o.optDouble("graphY",80.0).toFloat(),o.optString("gmNote",""))};s.tokens.clear();j.getJSONArray("tokens").eachObj{s.tokens+=Token(it.getString("id"),it.getString("label"),it.getDouble("x").toFloat(),it.getDouble("y").toFloat(),it.getBoolean("visible"),it.optInt("hp",10),it.optInt("maxHp",10),it.optBoolean("revealsFog",false),it.optDouble("visionRadius",110.0).toFloat(),runCatching{TokenKind.valueOf(it.optString("kind","HERO"))}.getOrDefault(TokenKind.HERO),it.optString("imageUri").takeIf{x->x.isNotBlank()&&x!="null"},it.optBoolean("revealWhenSeen",false))};s.fogHoles.clear();j.getJSONArray("holes").eachObj{s.fogHoles+=FogHole(it.getDouble("x").toFloat(),it.getDouble("y").toFloat(),it.getDouble("r").toFloat())};s.currentSceneId=j.optString("current",s.scenes.firstOrNull()?.id?:"");s.mapUri=j.optString("map").takeIf{it.isNotBlank()&&it!="null"};s.fogEnabled=j.optBoolean("fog",true);s.creatures.clear();j.optJSONArray("creatures")?.eachObj{o->val stats=mutableListOf<StatField>();o.optJSONArray("stats")?.eachObj{stats+=StatField(it.optString("name","Характеристика"),it.optInt("mod",0))};while(stats.size<6)stats+=StatField("Характеристика ${stats.size+1}",0);s.creatures+=CreatureCard(o.optString("id",UUID.randomUUID().toString()),o.optString("name","Существо"),o.optInt("hp",10),o.optInt("maxHp",10),o.optInt("tempHp",0),o.optInt("kd",o.optInt("cd",10)),o.optString("image").takeIf{it.isNotBlank()&&it!="null"},stats.take(6).toMutableList())};s.maps.clear();j.optJSONArray("maps")?.eachObj{o->val ts=mutableListOf<Token>();o.optJSONArray("tokens")?.eachObj{t->ts+=Token(t.optString("id",UUID.randomUUID().toString()),t.optString("label","Фишка"),t.optDouble("x",300.0).toFloat(),t.optDouble("y",300.0).toFloat(),t.optBoolean("visible",true),t.optInt("hp",10),t.optInt("maxHp",10),t.optBoolean("revealsFog",false),t.optDouble("visionRadius",110.0).toFloat(),runCatching{TokenKind.valueOf(t.optString("kind","HERO"))}.getOrDefault(TokenKind.HERO),t.optString("imageUri").takeIf{x->x.isNotBlank()&&x!="null"},t.optBoolean("revealWhenSeen",false))};val hs=mutableListOf<FogHole>();o.optJSONArray("holes")?.eachObj{h->hs+=FogHole(h.optDouble("x").toFloat(),h.optDouble("y").toFloat(),h.optDouble("r",90.0).toFloat())};val ms=mutableListOf<MapMarker>();o.optJSONArray("markers")?.eachObj{mk->ms+=MapMarker(mk.optString("id",UUID.randomUUID().toString()),mk.optString("label","Маркер"),mk.optDouble("x",320.0).toFloat(),mk.optDouble("y",320.0).toFloat(),mk.optBoolean("visible",true),mk.optString("kind","MARKER"))};s.maps+=BattleMap(o.optString("id",UUID.randomUUID().toString()),o.optString("name","Карта"),o.optString("uri").takeIf{it.isNotBlank()&&it!="null"},ts,hs,ms)};if(s.maps.isEmpty())s.maps+=BattleMap(name="Карта 1",uri=s.mapUri,tokens=s.tokens.toMutableList(),fogHoles=s.fogHoles.toMutableList());s.currentMapId=j.optString("currentMap",s.maps.first().id).takeIf{id->s.maps.any{it.id==id}}?:s.maps.first().id;s.syncLegacyFromMap();s.fogTool=runCatching{FogTool.valueOf(j.optString("fogTool","REVEAL"))}.getOrDefault(FogTool.REVEAL);s.gridEnabled=j.optBoolean("gridEnabled",false);s.gridSize=j.optDouble("gridSize",72.0).toFloat();s.snapToGrid=j.optBoolean("snapToGrid",false);s.mapZoom=j.optDouble("mapZoom",1.0).toFloat();s.mapPanX=j.optDouble("mapPanX",0.0).toFloat();s.mapPanY=j.optDouble("mapPanY",0.0).toFloat();s.showTokenLayer=j.optBoolean("showTokenLayer",true);s.showMarkerLayer=j.optBoolean("showMarkerLayer",true);s.playerMode=runCatching{PlayerMode.valueOf(j.optString("mode","SCENE"))}.getOrDefault(PlayerMode.SCENE)}}}

 fun exportAdventure(c:Context,s:AppState,uri:Uri):String { saveWorkspace(c,s); val a=s.adventures.firstOrNull{it.id==s.currentAdventureId}?:return "Нет выбранного приключения"; val cp=s.campaigns.firstOrNull{it.id==a.campaignId}; return exportPackage(c,uri,"adventure",cp,listOf(a)) }
 fun exportCampaign(c:Context,s:AppState,uri:Uri):String { saveWorkspace(c,s); val cp=s.campaigns.firstOrNull{it.id==s.currentCampaignId}?:return "Нет выбранной кампании"; return exportPackage(c,uri,"campaign",cp,s.adventures.filter{it.campaignId==cp.id}) }
 private fun exportPackage(c:Context,uri:Uri,kind:String,cp:Campaign?,ads:List<AdventureMeta>):String=runCatching{
  val manifest=JSONObject().put("format","miir-presenter-backup").put("version",1).put("kind",kind).put("campaign",JSONObject().put("id",cp?.id).put("name",cp?.name?:"Импорт")).put("adventures",JSONArray())
  val media=linkedMapOf<String,String>(); val payloads=mutableListOf<Pair<String,String>>()
  ads.forEachIndexed{i,a->val raw=c.openFileInput(file(a.id)).bufferedReader().readText();var cooked=raw;val root=JSONObject(raw);val uris=linkedSetOf<String>();collectUris(root,uris);uris.forEach{u->if(!media.containsKey(u)){media[u]="media/${media.size}"};cooked=cooked.replace(u,"miirasset://${media[u]}")};val entry="adventures/$i.json";payloads+=entry to cooked;manifest.getJSONArray("adventures").put(JSONObject().put("id",a.id).put("name",a.name).put("entry",entry))}
  c.contentResolver.openOutputStream(uri,"w")!!.use{out->ZipOutputStream(out).use{z->z.putNextEntry(ZipEntry("manifest.json"));z.write(manifest.toString().toByteArray());z.closeEntry();payloads.forEach{(n,t)->z.putNextEntry(ZipEntry(n));z.write(t.toByteArray());z.closeEntry()};media.forEach{(u,n)->runCatching{c.contentResolver.openInputStream(Uri.parse(u))?.use{inp->z.putNextEntry(ZipEntry(n));inp.copyTo(z);z.closeEntry()}}}}};"Экспорт готов: ${ads.size} приключ."}.getOrElse{"Ошибка экспорта: ${it.message}"}
 private fun collectUris(v:Any?,out:MutableSet<String>){when(v){is JSONObject->v.keys().forEachRemaining{k->collectUris(v.opt(k),out)};is JSONArray->for(i in 0 until v.length())collectUris(v.opt(i),out);is String->if(v.startsWith("content://")||v.startsWith("file://"))out+=v}}
 fun importBackup(c:Context,s:AppState,uri:Uri):String=runCatching{
  saveWorkspace(c,s);val entries=mutableMapOf<String,ByteArray>();c.contentResolver.openInputStream(uri)!!.use{inp->ZipInputStream(inp).use{z->while(true){val e=z.nextEntry?:break;entries[e.name]=z.readBytes();z.closeEntry()}}};val m=JSONObject(entries["manifest.json"]?.toString(Charsets.UTF_8)?:error("manifest.json не найден"));require(m.optString("format")=="miir-presenter-backup"){"Неизвестный формат"};val oldCp=m.getJSONObject("campaign");val cpId=UUID.randomUUID().toString();val cp=Campaign(cpId,oldCp.optString("name","Импортированная кампания"));s.campaigns+=cp;val mediaMap=mutableMapOf<String,String>();entries.filterKeys{it.startsWith("media/")}.forEach{(n,b)->val dir=File(c.filesDir,"imported_media").apply{mkdirs()};val f=File(dir,"${UUID.randomUUID()}_${n.substringAfterLast('/')}");f.writeBytes(b);mediaMap["miirasset://$n"]=Uri.fromFile(f).toString()};val arr=m.getJSONArray("adventures");var first="";for(i in 0 until arr.length()){val a=arr.getJSONObject(i);val id=UUID.randomUUID().toString();if(first.isBlank())first=id;val meta=AdventureMeta(id,cpId,a.optString("name","Импортированное приключение"));s.adventures+=meta;var txt=entries[a.getString("entry")]!!.toString(Charsets.UTF_8);mediaMap.forEach{(from,to)->txt=txt.replace(from,to)};c.openFileOutput(file(id),Context.MODE_PRIVATE).bufferedWriter().use{it.write(txt)}};s.currentCampaignId=cpId;s.currentAdventureId=first;if(first.isNotBlank()){resetAdventure(s);loadAdventure(c,s,file(first))};saveIndex(c,s);"Импортировано: ${arr.length()} приключ."}.getOrElse{"Ошибка импорта: ${it.message}"}

 private inline fun JSONArray.eachObj(block:(JSONObject)->Unit){for(i in 0 until length())block(getJSONObject(i))}
}

class WifiPlayerServer(private val c:Context,private val s:AppState){@Volatile private var running=false;private var socket:ServerSocket?=null;val address:String get()="http://${localIp()}:8787"
 fun start(){if(running)return;running=true;thread(isDaemon=true,name="MiirWifiPlayer"){runCatching{ServerSocket(8787).also{socket=it}.use{ss->while(running){val client=ss.accept();thread(isDaemon=true){handle(client)}}}}}}
 fun stop(){running=false;runCatching{socket?.close()}}
 private fun handle(client:Socket){client.use{val input=it.getInputStream().bufferedReader();val line=input.readLine()?:return;while(input.readLine()?.isNotEmpty()==true){};val path=line.split(" ").getOrNull(1)?:"/";when{path=="/"->respond(it,"text/html; charset=utf-8",HTML.toByteArray());path=="/state"->respond(it,"application/json",snapshot().toString().toByteArray());path.startsWith("/asset?")->serveAsset(it,URLDecoder.decode(path.substringAfter("uri="),"UTF-8"));else->respond(it,"text/plain","404".toByteArray(),"404 Not Found")}}}
 private fun serveAsset(sock:Socket,uri:String){runCatching{val type=c.contentResolver.getType(Uri.parse(uri))?:"application/octet-stream";val bytes=c.contentResolver.openInputStream(Uri.parse(uri))!!.use{it.readBytes()};respond(sock,type,bytes)}.onFailure{respond(sock,"text/plain","asset unavailable".toByteArray(),"404 Not Found")}}
 private fun respond(sock:Socket,type:String,body:ByteArray,status:String="200 OK"){val o=sock.getOutputStream();o.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray());o.write(body);o.flush()}
 private fun snapshot():JSONObject=JSONObject().apply{put("mode",s.playerMode.name);put("sceneTitle",s.currentScene?.title?:"");put("sceneAsset",s.currentScene?.assetUri);put("map",s.mapUri);put("fog",s.fogEnabled);put("zoom",s.mapZoom);put("panX",s.mapPanX);put("panY",s.mapPanY);put("markers",JSONArray().apply{s.markers.filter{it.visible}.forEach{m->put(JSONObject().put("label",m.label).put("x",m.x).put("y",m.y).put("kind",m.kind))}});put("tokens",JSONArray().apply{s.tokens.filter{it.visible}.forEach{put(JSONObject().put("label",it.label).put("x",it.x).put("y",it.y).put("hp",it.hp).put("maxHp",it.maxHp).put("revealsFog",it.revealsFog).put("visionRadius",it.visionRadius).put("kind",it.kind.name).put("imageUri",it.imageUri).put("revealWhenSeen",it.revealWhenSeen))}});put("holes",JSONArray().apply{s.fogHoles.forEach{put(JSONObject().put("x",it.x).put("y",it.y).put("r",it.radius))}})}
 private fun localIp():String=runCatching{NetworkInterface.getNetworkInterfaces().toList().flatMap{it.inetAddresses.toList()}.firstOrNull{!it.isLoopbackAddress&&it is Inet4Address}?.hostAddress?:"127.0.0.1"}.getOrDefault("127.0.0.1")
 companion object{private const val HTML="""<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><style>html,body{margin:0;background:#000;width:100%;height:100%;overflow:hidden}#scene{width:100%;height:100%;object-fit:contain}canvas{width:100%;height:100%;display:block}#title{color:white;font:5vw sans-serif;position:absolute;inset:0;display:grid;place-items:center}</style></head><body><img id='scene' hidden><canvas id='map' hidden></canvas><div id='title'></div><script>const A=u=>'/asset?uri='+encodeURIComponent(u);async function tick(){try{let s=await(await fetch('/state',{cache:'no-store'})).json(),im=document.querySelector('#scene'),cv=document.querySelector('#map'),ti=document.querySelector('#title');im.hidden=cv.hidden=true;ti.textContent='';if(s.mode==='BLACK'){}else if(s.mode==='SCENE'){if(s.sceneAsset){im.src=A(s.sceneAsset);im.hidden=false}else ti.textContent=s.sceneTitle}else{cv.hidden=false;let d=devicePixelRatio||1,w=innerWidth*d,h=innerHeight*d;cv.width=w;cv.height=h;let x=cv.getContext('2d');x.clearRect(0,0,w,h);if(s.map){let bg=new Image();bg.src=A(s.map);await bg.decode().catch(()=>{});let q=Math.min(w/bg.width,h/bg.height),ox=(w-bg.width*q)/2,oy=(h-bg.height*q)/2;x.drawImage(bg,ox,oy,bg.width*q,bg.height*q)}for(let t of s.tokens){x.beginPath();x.arc(t.x/3*d,t.y/3*d,24*d,0,7);x.fillStyle='white';x.fill();x.fillStyle='black';x.font=14*d+'px sans-serif';x.textAlign='center';x.fillText(t.label,t.x/3*d,t.y/3*d+5*d)}if(s.fog){x.save();x.fillStyle='rgba(0,0,0,.82)';x.fillRect(0,0,w,h);x.globalCompositeOperation='destination-out';for(let f of s.holes){x.beginPath();x.arc(f.x*d,f.y*d,f.r*d,0,7);x.fill()}x.restore()}}}catch(e){}setTimeout(tick,700)}tick()</script></body></html>"""}
}
