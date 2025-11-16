// services/evoext/EvoStatModifierExt.java
package services.evoext;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import l2.commons.lang.ArrayUtils;
import l2.commons.listener.Listener;
import l2.gameserver.Config;
import l2.gameserver.data.xml.holder.ItemHolder;
import l2.gameserver.listener.actor.player.OnPlayerEnterListener;
import l2.gameserver.model.Creature;
import l2.gameserver.model.GameObjectsStorage;
import l2.gameserver.model.Player;
import l2.gameserver.model.actor.listener.PlayerListenerList;
import l2.gameserver.model.items.Inventory;
import l2.gameserver.model.items.ItemInstance;
import l2.gameserver.model.items.PcInventory;
import l2.gameserver.scripts.ScriptFile;
import l2.gameserver.stats.Env;
import l2.gameserver.stats.Stats;
import l2.gameserver.stats.funcs.Func;
import l2.gameserver.templates.item.ItemTemplate;
import org.apache.commons.lang3.StringUtils;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extended stat modifier loaded from a separate XML file:
 *
 *   DATAPACK_ROOT/data/stats_custom_mod_ext.xml
 *
 * Features:
 *   - Multiple classId in <player classId="...">
 *   - Multiple classId in <targetPlayer classId="...">
 *   - Multiple itemId in <equipedWith itemId="...">
 *
 * Does not touch or depend on core StatModifier implementation.
 */
public final class EvoStatModifierExt implements ScriptFile, OnPlayerEnterListener
{
    private static final Logger LOG = LoggerFactory.getLogger(EvoStatModifierExt.class);

    private static final String CONFIG_RELATIVE_PATH = "data/stats_custom_mod_ext.xml";
    private static final EvoStatModifierExt INSTANCE = new EvoStatModifierExt();

    /**
     * Stats -> (playerClassId -> list of modifiers)
     */
    private static Map<Stats, Map<Integer, List<StatMod>>> MODS = Collections.emptyMap();

    private static File getConfigFile()
    {
        return new File(Config.DATAPACK_ROOT, CONFIG_RELATIVE_PATH);
    }

    // =====================================================================
    // Script lifecycle
    // =====================================================================

    @Override
    public void onLoad()
    {
        File configFile = getConfigFile();
        Map<Stats, Map<Integer, List<StatMod>>> loaded = loadConfig(configFile);

        if (loaded.isEmpty())
        {
            LOG.info("EvoStatModifierExt: no modifiers loaded (file: {}). Module is idle.", configFile.getPath());
            MODS = Collections.emptyMap();
            return;
        }

        MODS = loaded;

        PlayerListenerList.addGlobal((Listener) INSTANCE);
        LOG.info("EvoStatModifierExt: enabled. Config={}, stats={}", configFile.getPath(), Integer.valueOf(MODS.size()));
    }

    @Override
    public void onReload()
    {
        onShutdown();
        onLoad();
    }

    @Override
    public void onShutdown()
    {
        PlayerListenerList.removeGlobal((Listener) INSTANCE);

        if (!MODS.isEmpty())
        {
            for (Player player : GameObjectsStorage.getAllPlayersForIterate())
            {
                removeFromPlayer(player);
            }
        }

        MODS = Collections.emptyMap();
    }

    // =====================================================================
    // Player listener
    // =====================================================================

    @Override
    public void onPlayerEnter(Player player)
    {
        if (player == null || MODS.isEmpty())
        {
            return;
        }

        applyToPlayer(player);
    }

    private void removeFromPlayer(Player player)
    {
        if (player != null)
        {
            player.removeStatsOwner(this);
        }
    }

    private void applyToPlayer(Player player)
    {
        if (player == null || MODS.isEmpty())
        {
            return;
        }

        player.removeStatsOwner(this);

        for (Map.Entry<Stats, Map<Integer, List<StatMod>>> entry : MODS.entrySet())
        {
            final Map<Integer, List<StatMod>> byClass = entry.getValue();

            player.addStatFunc(new Func(entry.getKey(), 80, this)
            {
                @Override
                public void calc(Env env)
                {
                    if (env.character == null || !env.character.isPlayer())
                    {
                        return;
                    }

                    Player p = env.character.getPlayer();
                    if (p == null)
                    {
                        return;
                    }

                    List<StatMod> modsForClass = byClass.get(Integer.valueOf(p.getActiveClassId()));
                    if (modsForClass == null || modsForClass.isEmpty())
                    {
                        return;
                    }

                    double mul = 1.0;
                    double add = 0.0;

                    for (StatMod mod : modsForClass)
                    {
                        if (!mod.test(p, env.target))
                        {
                            continue;
                        }

                        mul += mod.getMul() - 1.0;
                        add += mod.getAdd();
                    }

                    env.value *= mul;
                    env.value += add;
                }
            });
        }
    }

    // =====================================================================
    // Config loading
    // =====================================================================

    private Map<Stats, Map<Integer, List<StatMod>>> loadConfig(File file)
    {
        if (!file.exists())
        {
            LOG.info("EvoStatModifierExt: config file not found: {}", file.getPath());
            return Collections.emptyMap();
        }

        try
        {
            SAXReader reader = new SAXReader(true);
            Document doc = reader.read(file);
            Element root = doc.getRootElement();

            if (!"list".equalsIgnoreCase(root.getName()))
            {
                throw new IllegalStateException("Root element <list> expected");
            }

            boolean enabled = Boolean.parseBoolean(root.attributeValue("enabled", "false"));
            if (!enabled)
            {
                LOG.info("EvoStatModifierExt: config disabled via 'enabled=\"false\"'.");
                return Collections.emptyMap();
            }

            Map<Stats, Map<Integer, List<StatMod>>> result = new HashMap<Stats, Map<Integer, List<StatMod>>>();

            for (Iterator<?> it = root.elementIterator(); it.hasNext(); )
            {
                Element playerElement = (Element) it.next();
                if (!"player".equalsIgnoreCase(playerElement.getName()))
                {
                    continue;
                }

                int[] classIds = parseIdList(playerElement.attributeValue("classId"), "class");
                if (classIds.length == 0)
                {
                    continue;
                }

                OlyMode olyMode = OlyMode.valueOf(StringUtils.upperCase(playerElement.attributeValue("olyMode", "ANY")));

                for (int classId : classIds)
                {
                    List<StatMod> parsedMods = parsePlayerElement(playerElement, classId, olyMode, new ModCond[0]);
                    if (parsedMods.isEmpty())
                    {
                        continue;
                    }

                    for (StatMod mod : parsedMods)
                    {
                        Map<Integer, List<StatMod>> byClass = result.get(mod.getStat());
                        if (byClass == null)
                        {
                            byClass = new HashMap<Integer, List<StatMod>>();
                            result.put(mod.getStat(), byClass);
                        }

                        List<StatMod> listForClass = byClass.get(Integer.valueOf(classId));
                        if (listForClass == null)
                        {
                            listForClass = new ArrayList<StatMod>();
                            byClass.put(Integer.valueOf(classId), listForClass);
                        }

                        listForClass.add(mod);
                    }
                }
            }

            return result;
        }
        catch (Exception e)
        {
            LOG.error("EvoStatModifierExt: error loading config {}: {}", file.getPath(), e.getMessage(), e);
            return Collections.emptyMap();
        }
    }
    private static int[] parseIdList(String attr, String what)
    {
        if (attr == null)
        {
            return new int[0];
        }

        String trimmed = attr.trim();
        if (trimmed.isEmpty())
        {
            return new int[0];
        }

        String[] tokens = StringUtils.split(trimmed, ',');
        List<Integer> values = new ArrayList<Integer>(tokens.length);

        for (String token : tokens)
        {
            if (token == null)
            {
                continue;
            }

            String s = token.trim();
            if (s.isEmpty())
            {
                continue;
            }

            try
            {
                values.add(Integer.valueOf(Integer.parseInt(s)));
            }
            catch (NumberFormatException e)
            {
                LOG.warn("EvoStatModifierExt: invalid {} id '{}' in '{}'", new Object[]{ what, s, attr });
            }
        }

        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++)
        {
            result[i] = values.get(i).intValue();
        }

        return result;
    }

    private List<StatMod> parseMulAdd(Element element, ModCond[] conditions)
    {
        List<StatMod> result = new ArrayList<StatMod>();

        for (Iterator<?> it = element.elementIterator(); it.hasNext(); )
        {
            Element e = (Element) it.next();
            StatMod mod;

            if ("mul".equalsIgnoreCase(e.getName()))
            {
                mod = new StatMod(conditions, Stats.valueOfXml(e.attributeValue("stat")));
                double val = Double.parseDouble(e.attributeValue("val"));
                mod.addMul(val - 1.0);
                result.add(mod);
            }
            else if ("add".equalsIgnoreCase(e.getName()))
            {
                mod = new StatMod(conditions, Stats.valueOfXml(e.attributeValue("stat")));
                double val = Double.parseDouble(e.attributeValue("val"));
                mod.setAdd(val);
                result.add(mod);
            }
        }

        return result;
    }

    private List<StatMod> parseTargetPlayerElement(Element element, final int targetClassId, ModCond[] conditions)
    {
        List<StatMod> result = new ArrayList<StatMod>();

        conditions = (ModCond[]) ArrayUtils.add(conditions, new ModCond()
        {
            @Override
            public boolean test(Player player, Creature target)
            {
                return target != null
                        && target.isPlayer()
                        && target.getPlayer().getActiveClassId() == targetClassId;
            }
        });

        result.addAll(parseMulAdd(element, conditions));
        return result;
    }

    private List<StatMod> parseEquipedWithElement(Element element, ModCond[] conditions)
    {
        List<StatMod> result = new ArrayList<StatMod>();

        int[] itemIds = parseIdList(element.attributeValue("itemId"), "item");
        if (itemIds.length == 0)
        {
            return result;
        }

        final List<ItemTemplate> templates = new ArrayList<ItemTemplate>(itemIds.length);
        for (int id : itemIds)
        {
            ItemTemplate template = ItemHolder.getInstance().getTemplate(id);
            if (template != null)
            {
                templates.add(template);
            }
        }

        if (templates.isEmpty())
        {
            return result;
        }

        conditions = (ModCond[]) ArrayUtils.add(conditions, new ModCond()
        {
            private boolean isEquipped(PcInventory inventory, ItemTemplate template)
            {
                if (inventory == null || template == null)
                {
                    return false;
                }

                int slot = Inventory.getPaperdollIndex(template.getBodyPart());
                if (slot < 0)
                {
                    return false;
                }

                // 14 -> 7 hack остава същия като в core
                ItemInstance item = inventory.getPaperdollItem(slot != 14 ? slot : 7);
                return item != null && item.getItemId() == template.getItemId();
            }

            @Override
            public boolean test(Player player, Creature target)
            {
                if (player == null)
                {
                    return false;
                }

                PcInventory inv = player.getInventory();
                for (ItemTemplate template : templates)
                {
                    if (isEquipped(inv, template))
                    {
                        return true;
                    }
                }

                return false;
            }
        });

        result.addAll(parseMulAdd(element, conditions));
        return result;
    }

    private List<StatMod> parsePlayerElement(Element element, final int playerClassId, final OlyMode olyMode, ModCond[] baseConditions)
    {
        List<StatMod> result = new ArrayList<StatMod>();

        ModCond classAndOlyCond = new ModCond()
        {
            @Override
            public boolean test(Player player, Creature target)
            {
                if (player == null || player.getActiveClassId() != playerClassId)
                {
                    return false;
                }

                boolean onOly = player.isOlyParticipant() || player.isOlyCompetitionStarted();

                switch (olyMode)
                {
                    case OLY_ONLY:
                        return onOly;
                    case NON_OLY_ONLY:
                        return !onOly;
                    default:
                        return true;
                }
            }
        };

        ModCond[] conditions = (ModCond[]) ArrayUtils.add(baseConditions, classAndOlyCond);

        for (Iterator<?> it = element.elementIterator(); it.hasNext(); )
        {
            Element child = (Element) it.next();

            if ("targetPlayer".equalsIgnoreCase(child.getName()))
            {
                int[] targetIds = parseIdList(child.attributeValue("classId"), "target class");
                for (int targetId : targetIds)
                {
                    result.addAll(parseTargetPlayerElement(child, targetId, conditions));
                }
            }
            else if ("equipedWith".equalsIgnoreCase(child.getName()))
            {
                result.addAll(parseEquipedWithElement(child, conditions));
            }
        }

        // direct mul/add on <player>
        result.addAll(parseMulAdd(element, conditions.clone()));
        return result;
    }

    // =====================================================================
    // Internal model
    // =====================================================================

    private static final class StatMod
    {
        private final ModCond[] _conditions;
        private final Stats _stat;
        private double _mul = 1.0;
        private double _add = 0.0;

        private StatMod(ModCond[] conditions, Stats stat)
        {
            _conditions = conditions.clone();
            _stat = stat;
        }

        public boolean test(Player player, Creature target)
        {
            for (ModCond condition : _conditions)
            {
                if (!condition.test(player, target))
                {
                    return false;
                }
            }
            return true;
        }

        public Stats getStat()
        {
            return _stat;
        }

        public double getMul()
        {
            return _mul;
        }

        public void addMul(double delta)
        {
            _mul += delta;
        }

        public double getAdd()
        {
            return _add;
        }

        public void setAdd(double value)
        {
            _add = value;
        }
    }

    private interface ModCond
    {
        boolean test(Player player, Creature target);
    }

    private enum OlyMode
    {
        OLY_ONLY,
        NON_OLY_ONLY,
        ANY
    }
}
