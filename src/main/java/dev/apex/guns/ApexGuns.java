package dev.apex.guns;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class ApexGuns extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private NamespacedKey typeKey, idKey, roundsKey, ammoKey;
    private final Map<String,Gun> guns = new LinkedHashMap<>();
    private final Map<UUID,State> states = new HashMap<>();
    private final Map<UUID,String> cursorDrops = new HashMap<>();
    private final Set<UUID> damageInProgress = new HashSet<>();
    private long tick;
    private record Gun(String id,String name,int magazine,double damage,double range,
                       int cooldown,int reload,int pellets,double spread,double aimSpread,float recoil) {}
    private static final class State {
        boolean aiming;
        float previousSpeed;
        String heldId, reloadId;
        int reloadSlot, reloadTotal;
        long nextShot, nextAim, reloadEnd;
    }
    @Override public void onEnable() {
        typeKey=new NamespacedKey(this,"gun"); idKey=new NamespacedKey(this,"serial");
        roundsKey=new NamespacedKey(this,"rounds"); ammoKey=new NamespacedKey(this,"ammo");
        saveDefaultConfig(); loadGuns();
        getServer().getPluginManager().registerEvents(this,this);
        PluginCommand command=Objects.requireNonNull(getCommand("guns"));
        command.setExecutor(this); command.setTabCompleter(this);
        Bukkit.getScheduler().runTaskTimer(this,this::pulse,1L,1L);
    }
    @Override public void onDisable() {
        for(Player p:Bukkit.getOnlinePlayers()) clear(p);
        states.clear(); cursorDrops.clear(); damageInProgress.clear();
    }
    private void loadGuns() {
        guns.clear();
        ConfigurationSection all=Objects.requireNonNull(getConfig().getConfigurationSection("weapons"),"Missing weapons section");
        for(String id:List.of("viper","phantom","breach","spectre")) {
            ConfigurationSection c=all.getConfigurationSection(id); if(c==null) continue;
            guns.put(id,new Gun(id,c.getString("name",id),Math.max(1,c.getInt("magazine",10)),
                Math.max(0,c.getDouble("damage",5)),Math.max(1,Math.min(256,c.getDouble("range",80))),
                Math.max(1,c.getInt("cooldown-ticks",5)),Math.max(1,c.getInt("reload-ticks",40)),
                Math.max(1,Math.min(16,c.getInt("pellets",1))),Math.max(0,c.getDouble("spread",0.02)),
                Math.max(0,c.getDouble("aim-spread",0.003)),(float)c.getDouble("recoil",1)));
        }
    }
    private State state(Player p) { return states.computeIfAbsent(p.getUniqueId(),id->new State()); }
    private String tag(ItemStack item,NamespacedKey key) {
        return item==null || !item.hasItemMeta()?null:item.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.STRING);
    }
    private Gun gun(ItemStack item) {
        String id=tag(item,typeKey); return id==null?null:guns.get(id);
    }
    private String serial(ItemStack item) { return tag(item,idKey); }
    private int rounds(ItemStack item) {
        return item.getItemMeta().getPersistentDataContainer().getOrDefault(roundsKey,PersistentDataType.INTEGER,0);
    }
    private void model(ItemStack item,Gun g,boolean aim) {
        ItemMeta meta=item.getItemMeta(); meta.setItemModel(new NamespacedKey("apexguns",g.id+(aim?"_ads":""))); item.setItemMeta(meta);
    }
    private void setRounds(ItemStack item,Gun g,int amount) {
        int n=Math.max(0,Math.min(g.magazine,amount)); ItemMeta meta=item.getItemMeta();
        meta.getPersistentDataContainer().set(roundsKey,PersistentDataType.INTEGER,n);
        meta.lore(List.of(Component.text(n+" / "+g.magazine+" rounds",NamedTextColor.AQUA),
            Component.text("Right-click fire | Left-click aim",NamedTextColor.GRAY),
            Component.text("Q reload | E cursor-drop to discard",NamedTextColor.DARK_GRAY)));
        item.setItemMeta(meta);
    }
    private ItemStack makeGun(Gun g) {
        ItemStack item=new ItemStack(Material.IRON_HORSE_ARMOR); ItemMeta meta=item.getItemMeta();
        meta.itemName(Component.text(g.name,NamedTextColor.AQUA)); meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(typeKey,PersistentDataType.STRING,g.id);
        meta.getPersistentDataContainer().set(idKey,PersistentDataType.STRING,UUID.randomUUID().toString());
        item.setItemMeta(meta); model(item,g,false); setRounds(item,g,g.magazine); return item;
    }
    private ItemStack ammo(Gun g,int amount) {
        ItemStack item=new ItemStack(Material.PAPER,amount); ItemMeta meta=item.getItemMeta();
        meta.itemName(Component.text(g.name+" ammunition",NamedTextColor.GOLD));
        meta.getPersistentDataContainer().set(ammoKey,PersistentDataType.STRING,g.id);
        meta.setItemModel(new NamespacedKey("apexguns","ammo")); item.setItemMeta(meta); return item;
    }
    private int reserve(Player p,Gun g) {
        int n=0; for(ItemStack item:p.getInventory().getStorageContents()) if(g.id.equals(tag(item,ammoKey))) n+=item.getAmount(); return n;
    }
    private int consume(Player p,Gun g,int wanted) {
        if(getConfig().getBoolean("infinite-reserve")) return wanted;
        int left=wanted;
        for(int slot=0;slot<36 && left>0;slot++) {
            ItemStack item=p.getInventory().getItem(slot); if(!g.id.equals(tag(item,ammoKey))) continue;
            int n=Math.min(left,item.getAmount()); left-=n;
            if(n==item.getAmount()) p.getInventory().setItem(slot,null); else item.setAmount(item.getAmount()-n);
        }
        return wanted-left;
    }
    private void aim(Player p,boolean enabled) {
        State s=state(p); ItemStack item=p.getInventory().getItemInMainHand(); Gun g=gun(item);
        if(enabled && g==null) return;
        if(enabled && !s.aiming) {
            s.previousSpeed=p.getWalkSpeed(); s.aiming=true;
            float multiplier=(float)Math.max(0.1,Math.min(1,getConfig().getDouble("aim-walk-multiplier",0.55)));
            p.setWalkSpeed(s.previousSpeed*multiplier); p.playSound(p.getLocation(),Sound.ITEM_SPYGLASS_USE,0.6f,1.2f);
        } else if(!enabled && s.aiming) { s.aiming=false; p.setWalkSpeed(s.previousSpeed); }
        if(g!=null) model(item,g,enabled);
    }
    private void normalize(Player p) {
        for(ItemStack item:p.getInventory().getContents()) { Gun g=gun(item); if(g!=null) model(item,g,false); }
        ItemStack cursor=p.getItemOnCursor(); Gun g=gun(cursor); if(g!=null) model(cursor,g,false);
    }
    private void clear(Player p) {
        State s=states.get(p.getUniqueId()); if(s!=null) { aim(p,false); s.reloadId=null; s.heldId=null; }
        normalize(p); p.sendActionBar(Component.empty());
    }
    private void reload(Player p) {
        if(!p.isOnline() || !p.hasPermission("apexguns.use") || p.isDead() || p.getGameMode()==GameMode.SPECTATOR) return;
        ItemStack item=p.getInventory().getItemInMainHand(); Gun g=gun(item); if(g==null) return;
        State s=state(p); if(s.reloadId!=null || rounds(item)>=g.magazine) return;
        if(!getConfig().getBoolean("infinite-reserve") && reserve(p,g)==0) {
            p.sendActionBar(Component.text("No ammunition for "+g.name,NamedTextColor.RED)); return;
        }
        aim(p,false); s.heldId=serial(item); s.reloadId=s.heldId;
        s.reloadSlot=p.getInventory().getHeldItemSlot(); s.reloadTotal=g.reload; s.reloadEnd=tick+g.reload;
        p.getWorld().playSound(p.getLocation(),Sound.BLOCK_IRON_DOOR_OPEN,0.6f,1.6f);
    }
    private void pulse() {
        tick++;
        for(Player p:Bukkit.getOnlinePlayers()) {
            State s=state(p); ItemStack item=p.getInventory().getItemInMainHand(); Gun g=gun(item); String id=serial(item);
            if(!Objects.equals(id,s.heldId)) { aim(p,false); normalize(p); s.reloadId=null; s.heldId=id; }
            if(g==null || p.isDead()) continue;
            if(s.reloadId!=null) {
                if(!Objects.equals(id,s.reloadId) || s.reloadSlot!=p.getInventory().getHeldItemSlot()) s.reloadId=null;
                else if(tick>=s.reloadEnd) {
                    int n=rounds(item); setRounds(item,g,n+consume(p,g,Math.max(0,g.magazine-n))); s.reloadId=null;
                    p.getWorld().playSound(p.getLocation(),Sound.BLOCK_IRON_DOOR_CLOSE,0.7f,1.8f);
                }
            }
            if(tick%2==0) {
                String text;
                if(s.reloadId!=null) {
                    int bars=Math.max(0,Math.min(12,(int)(12*(1-(s.reloadEnd-tick)/(double)s.reloadTotal))));
                    text=g.name+"  ["+"|".repeat(bars)+".".repeat(12-bars)+"] RELOADING";
                } else text=(s.aiming?"[ AIM ]  ":"")+g.name+"  "+rounds(item)+" / "+g.magazine+"  |  "+(getConfig().getBoolean("infinite-reserve")?"INF":reserve(p,g));
                p.sendActionBar(Component.text(text,s.reloadId==null?NamedTextColor.AQUA:NamedTextColor.GOLD));
            }
        }
    }
    private boolean targetAllowed(Player p,Entity e) {
        if(!(e instanceof LivingEntity) || e==p || e.isDead()) return false;
        return !(e instanceof Player q) || (getConfig().getBoolean("pvp",true) && p.getWorld().getPVP()
            && q.getGameMode()!=GameMode.CREATIVE && q.getGameMode()!=GameMode.SPECTATOR);
    }
    private void fire(Player p) {
        if(!p.hasPermission("apexguns.use") || p.isDead() || p.getGameMode()==GameMode.SPECTATOR) return;
        ItemStack item=p.getInventory().getItemInMainHand(); Gun g=gun(item); if(g==null) return;
        State s=state(p); if(s.reloadId!=null || tick<s.nextShot) return;
        if(rounds(item)<=0) { s.nextShot=tick+6; p.playSound(p.getLocation(),Sound.BLOCK_DISPENSER_FAIL,0.7f,1.7f); reload(p); return; }
        s.nextShot=tick+g.cooldown; setRounds(item,g,rounds(item)-1);
        Location eye=p.getEyeLocation(); Vector forward=eye.getDirection(); Location muzzle=eye.clone().add(forward.clone().multiply(0.65));
        p.getWorld().spawnParticle(Particle.FLAME,muzzle,3,0.02,0.02,0.02,0.005);
        p.getWorld().spawnParticle(Particle.SMOKE,muzzle,3,0.025,0.025,0.025,0.01);
        p.getWorld().playSound(eye,"apexguns.fire_"+g.id,SoundCategory.PLAYERS,1.4f,1.0f);
        Map<LivingEntity,Double> hits=new HashMap<>(); Set<LivingEntity> heads=new HashSet<>();
        double spread=s.aiming?g.aimSpread:g.spread;
        for(int pellet=0;pellet<g.pellets;pellet++) {
            ThreadLocalRandom random=ThreadLocalRandom.current();
            Vector dir=forward.clone().add(new Vector(random.nextGaussian()*spread,random.nextGaussian()*spread,random.nextGaussian()*spread)).normalize();
            RayTraceResult hit=p.getWorld().rayTrace(eye,dir,g.range,FluidCollisionMode.NEVER,true,0.03,e->targetAllowed(p,e));
            Vector end=hit==null?eye.toVector().add(dir.clone().multiply(g.range)):hit.getHitPosition();
            if(getConfig().getBoolean("tracers",true)) {
                double distance=eye.toVector().distance(end);
                for(double d=0.7;d<distance;d+=1.5) p.getWorld().spawnParticle(Particle.CRIT,eye.clone().add(dir.clone().multiply(d)),1,0,0,0,0);
            }
            if(hit!=null && hit.getHitEntity() instanceof LivingEntity target) {
                boolean head=hit.getHitPosition().getY()>=target.getEyeLocation().getY()-0.22;
                hits.merge(target,g.damage*(head?Math.max(1,getConfig().getDouble("headshot-multiplier",1.75)):1),Double::sum);
                if(head) heads.add(target);
            } else if(hit!=null && hit.getHitBlock()!=null) {
                p.getWorld().spawnParticle(Particle.BLOCK,hit.getHitPosition().toLocation(p.getWorld()),5,0.04,0.04,0.04,hit.getHitBlock().getBlockData());
            }
        }
        for(var entry:hits.entrySet()) {
            damageInProgress.add(p.getUniqueId());
            try { entry.getKey().damage(entry.getValue(),p); } finally { damageInProgress.remove(p.getUniqueId()); }
            p.playSound(p.getLocation(),Sound.ENTITY_EXPERIENCE_ORB_PICKUP,0.6f,heads.contains(entry.getKey())?1.9f:1.3f);
        }
        p.setRotation(p.getYaw(),Math.max(-89,Math.min(89,p.getPitch()-g.recoil*(s.aiming?0.55f:1f))));
    }
    private void toggle(Player p) {
        if(!p.hasPermission("apexguns.use") || p.isDead()) return;
        State s=state(p); if(tick<s.nextAim || s.reloadId!=null) return;
        s.heldId=serial(p.getInventory().getItemInMainHand()); s.nextAim=tick+5; aim(p,!s.aiming);
    }
    @EventHandler(priority=EventPriority.HIGH) public void interact(PlayerInteractEvent e) {
        if(e.getHand()!=EquipmentSlot.HAND || gun(e.getPlayer().getInventory().getItemInMainHand())==null) return;
        // Air interaction can be pre-cancelled by Bukkit even without a protection plugin.
        e.setCancelled(true);
        switch(e.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> fire(e.getPlayer());
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> toggle(e.getPlayer());
            default -> {}
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void melee(EntityDamageByEntityEvent e) {
        if(e.getDamager() instanceof Player p && gun(p.getInventory().getItemInMainHand())!=null
            && !damageInProgress.contains(p.getUniqueId())
            && (e.getCause()==EntityDamageEvent.DamageCause.ENTITY_ATTACK || e.getCause()==EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK)) {
            e.setCancelled(true); toggle(p);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breaking(BlockBreakEvent e) {
        if(gun(e.getPlayer().getInventory().getItemInMainHand())!=null) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drop(PlayerDropItemEvent e) {
        ItemStack item=e.getItemDrop().getItemStack(); Gun g=gun(item); if(g==null) return;
        String permit=cursorDrops.remove(e.getPlayer().getUniqueId());
        if(permit!=null && permit.equals(serial(item))) { model(item,g,false); e.getItemDrop().setItemStack(item); return; }
        e.setCancelled(true);
        String id=serial(item);
        Bukkit.getScheduler().runTask(this,()->{
            Player p=e.getPlayer();
            if(p.isOnline() && Objects.equals(id,serial(p.getInventory().getItemInMainHand()))) reload(p);
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void click(InventoryClickEvent e) {
        if(!(e.getWhoClicked() instanceof Player p)) return;
        if((e.getAction()==InventoryAction.DROP_ALL_SLOT || e.getAction()==InventoryAction.DROP_ONE_SLOT) && gun(e.getCurrentItem())!=null) {
            e.setCancelled(true); return;
        }
        if((e.getAction()==InventoryAction.DROP_ALL_CURSOR || e.getAction()==InventoryAction.DROP_ONE_CURSOR) && gun(e.getCursor())!=null) {
            if(e.getView().getType()!=InventoryType.CRAFTING || e.getRawSlot()!=-999) { e.setCancelled(true); return; }
            String id=serial(e.getCursor()); if(id==null) { e.setCancelled(true); return; }
            cursorDrops.put(p.getUniqueId(),id);
            Bukkit.getScheduler().runTask(this,()->cursorDrops.remove(p.getUniqueId(),id));
        }
    }
    @EventHandler public void open(InventoryOpenEvent e) { if(e.getPlayer() instanceof Player p) clear(p); }
    @EventHandler public void close(InventoryCloseEvent e) { if(e.getPlayer() instanceof Player p) clear(p); }
    @EventHandler public void held(PlayerItemHeldEvent e) { clear(e.getPlayer()); }
    @EventHandler(ignoreCancelled=true) public void swap(PlayerSwapHandItemsEvent e) { clear(e.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent e) {
        clear(e.getPlayer()); states.remove(e.getPlayer().getUniqueId()); cursorDrops.remove(e.getPlayer().getUniqueId());
    }
    @EventHandler public void join(PlayerJoinEvent e) { normalize(e.getPlayer()); }
    @EventHandler public void world(PlayerChangedWorldEvent e) { clear(e.getPlayer()); }
    @EventHandler(priority=EventPriority.HIGHEST) public void death(PlayerDeathEvent e) {
        clear(e.getEntity());
        if(!getConfig().getBoolean("keep-guns-on-death",true) || e.getKeepInventory()) return;
        Iterator<ItemStack> it=e.getDrops().iterator();
        while(it.hasNext()) { ItemStack item=it.next(); if(gun(item)!=null) { it.remove(); e.getItemsToKeep().add(item); } }
    }
    @EventHandler(ignoreCancelled=true) public void equip(PlayerInteractEntityEvent e) {
        if(gun(e.getPlayer().getInventory().getItem(e.getHand()))!=null) {
            e.setCancelled(true); if(e.getHand()==EquipmentSlot.HAND) fire(e.getPlayer());
        }
    }
    private void tell(CommandSender sender,String text) { sender.sendMessage(Component.text(text,NamedTextColor.AQUA)); }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] a) {
        if(a.length==0 || a[0].equalsIgnoreCase("help")) {
            tell(sender,"Right-click fire; left-click aim; Q reload; E > pick up gun > click outside to drop.");
            if(sender.hasPermission("apexguns.admin")) tell(sender,"/guns give <player> <viper|phantom|breach|spectre|all> | /guns ammo <player> <gun> <1-2304> | /guns reload");
            return true;
        }
        if(!sender.hasPermission("apexguns.admin")) { tell(sender,"Missing apexguns.admin permission."); return true; }
        if(a[0].equalsIgnoreCase("reload")) {
            for(Player p:Bukkit.getOnlinePlayers()) clear(p);
            reloadConfig(); loadGuns(); tell(sender,"Configuration reloaded."); return true;
        }
        if(a.length<3) { tell(sender,"Use /guns help"); return true; }
        Player p=Bukkit.getPlayerExact(a[1]); if(p==null) { tell(sender,"Player must be online (exact name)."); return true; }
        String id=a[2].toLowerCase(Locale.ROOT);
        if(a[0].equalsIgnoreCase("give")) {
            List<Gun> selected=id.equals("all")?new ArrayList<>(guns.values()):guns.containsKey(id)?List.of(guns.get(id)):List.of();
            if(selected.isEmpty()) { tell(sender,"Unknown gun."); return true; }
            int free=0; for(ItemStack item:p.getInventory().getStorageContents()) if(item==null || item.getType().isAir()) free++;
            if(free<selected.size()) { tell(sender,"Not enough free slots; no guns given."); return true; }
            for(Gun g:selected) p.getInventory().addItem(makeGun(g));
            tell(sender,"Gave guns to "+p.getName()); return true;
        }
        if(a[0].equalsIgnoreCase("ammo") && a.length==4 && guns.containsKey(id)) {
            int n; try { n=Integer.parseInt(a[3]); } catch(NumberFormatException ex) { tell(sender,"Invalid ammo amount."); return true; }
            if(n<1 || n>2304) { tell(sender,"Amount must be 1-2304."); return true; }
            Gun g=guns.get(id); ItemStack sample=ammo(g,1); int capacity=0;
            for(ItemStack item:p.getInventory().getStorageContents()) capacity+=item==null || item.getType().isAir()?64:item.isSimilar(sample)?Math.max(0,64-item.getAmount()):0;
            if(capacity<n) { tell(sender,"Not enough space; no ammo given."); return true; }
            while(n>0) { int batch=Math.min(64,n); p.getInventory().addItem(ammo(g,batch)); n-=batch; }
            tell(sender,"Gave ammunition to "+p.getName()); return true;
        }
        tell(sender,"Use /guns help"); return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String label,String[] a) {
        if(!sender.hasPermission("apexguns.admin")) return List.of("help");
        List<String> choices=new ArrayList<>();
        if(a.length==1) choices.addAll(List.of("give","ammo","reload","help"));
        else if(a.length==2) for(Player p:Bukkit.getOnlinePlayers()) choices.add(p.getName());
        else if(a.length==3) { choices.addAll(guns.keySet()); if(a[0].equalsIgnoreCase("give")) choices.add("all"); }
        else if(a.length==4 && a[0].equalsIgnoreCase("ammo")) choices.addAll(List.of("64","128","256"));
        String prefix=a[a.length-1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s->s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
