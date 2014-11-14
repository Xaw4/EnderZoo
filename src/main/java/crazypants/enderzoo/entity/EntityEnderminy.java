package crazypants.enderzoo.entity;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EntityDamageSource;
import net.minecraft.util.EntityDamageSourceIndirect;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.EnderTeleportEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import crazypants.enderzoo.EnderZoo;
import crazypants.enderzoo.config.Config;
import crazypants.enderzoo.vec.VecUtil;

public class EntityEnderminy extends EntityMob implements IEnderZooMob{

  public static String NAME = "enderzoo.Enderminy";
  public static final int EGG_BG_COL = 0x27624D;
  public static final int EGG_FG_COL = 0x212121;

  private static final int MAX_RND_TP_DISTANCE = 32;

  private static final UUID attackingSpeedBoostModifierUUID = UUID.fromString("020E0DFB-87AE-4653-9556-831010E291B0");
  private static final AttributeModifier attackingSpeedBoostModifier = (new AttributeModifier(attackingSpeedBoostModifierUUID, "Attacking speed boost",
      6.2, 0)).setSaved(false);
  
  /**
   * Counter to delay the teleportation of an enderman towards the currently
   * attacked target
   */
  private int teleportDelay;
  /**
   * A player must stare at an enderman for 5 ticks before it becomes
   * aggressive. This field counts those ticks.
   */
  private int stareTimer;
  private Entity lastEntityToAttack;
  private boolean isAggressive;

  private boolean attackIfLookingAtPlayer = Config.enderminyAttacksPlayerOnSight;
  private boolean attackCreepers = Config.enderminyAttacksCreepers;
  private boolean groupAgroEnabled = Config.enderminyGroupAgro;

  private final ClosestEntityComparator closestEntityComparator = new ClosestEntityComparator();

  public EntityEnderminy(World world) {
    super(world);
    setSize(0.6F * 0.5F, 2.9F * 0.25F);
    stepHeight = 1.0F;
  }

  @Override
  @SideOnly(Side.CLIENT)
  public float getShadowSize() {
    return 0.01F;
  }
 
  @Override
  protected boolean isValidLightLevel() {
    return Config.enderminySpawnInLitAreas ? true : super.isValidLightLevel();
  }

  @Override
  protected void applyEntityAttributes() {
    super.applyEntityAttributes();
    getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(0.3);
    MobInfo.ENDERMINY.applyAttributes(this);
  }

  @Override
  protected void entityInit() {
    super.entityInit();
    dataWatcher.addObject(16, new Byte((byte) 0));
    dataWatcher.addObject(17, new Byte((byte) 0));
    dataWatcher.addObject(18, new Byte((byte) 0));
  }

  @Override
  public boolean getCanSpawnHere() {
    boolean passedGrassCheck = true;
    if(Config.enderminySpawnOnlyOnGrass) {
      int i = MathHelper.floor_double(posX);
      int j = MathHelper.floor_double(boundingBox.minY);
      int k = MathHelper.floor_double(posZ);
      passedGrassCheck = worldObj.getBlock(i, j - 1, k) == Blocks.grass;
    }
    return posY > Config.enderminyMinSpawnY && super.getCanSpawnHere();
  }

  

  @Override
  protected Entity findPlayerToAttack() {

    if(attackIfLookingAtPlayer) {
      EntityPlayer entityplayer = worldObj.getClosestVulnerablePlayerToEntity(this, 64.0D);
      if(entityplayer != null) {
        if(shouldAttackPlayer(entityplayer)) {
          isAggressive = true;

          if(stareTimer == 0) {
            worldObj.playSoundEffect(entityplayer.posX, entityplayer.posY, entityplayer.posZ, "mob.endermen.stare", 1.0F, 1.0F);
          }

          if(stareTimer++ == 5) {
            stareTimer = 0;
            setScreaming(true);
            return entityplayer;
          }
        } else {
          stareTimer = 0;
        }
      }
    }
    if(attackCreepers) {
      int range = 16;
      AxisAlignedBB bb = AxisAlignedBB.getBoundingBox(posX - range, posY - range, posZ - range, posX + range, posY + range, posZ + range);
      List<EntityCreeper> creepers = worldObj.getEntitiesWithinAABB(EntityCreeper.class, bb);
      if(creepers != null && !creepers.isEmpty()) {
        Collections.sort(creepers, closestEntityComparator);
        for (EntityCreeper creeper : creepers) {
          if(creeper.canEntityBeSeen(this)) {
            return creeper;
          }
        }
      }
    }
    return null;
  }

  /**
   * Checks to see if this enderman should be attacking this player
   */
  private boolean shouldAttackPlayer(EntityPlayer player) {

    ItemStack itemstack = player.inventory.armorInventory[3];
    if(itemstack != null && itemstack.getItem() == Item.getItemFromBlock(Blocks.pumpkin)) {
      return false;
    } else {

      Vec3 relativePlayerEyePos = Vec3.createVectorHelper(
          posX - player.posX,
          boundingBox.minY + height / 2.0F - (player.posY + player.getEyeHeight()),
          posZ - player.posZ);

      double distance = relativePlayerEyePos.lengthVector();
      relativePlayerEyePos = relativePlayerEyePos.normalize();

      //NB: inverse of normal enderman, attack when this guy looks at the player instead of the other
      //way around
      Vec3 lookVec = getLook(1.0F).normalize();
      double dotTangent = -lookVec.dotProduct(relativePlayerEyePos);

      return dotTangent > 1.0D - 0.025D / distance;
    }
  }

  /**
   * Called frequently so the entity can update its state every tick as
   * required. For example, zombies and skeletons use this to react to sunlight
   * and start to burn.
   */
  @Override
  public void onLivingUpdate() {

    if(lastEntityToAttack != entityToAttack) {
      IAttributeInstance iattributeinstance = getEntityAttribute(SharedMonsterAttributes.movementSpeed);
      iattributeinstance.removeModifier(attackingSpeedBoostModifier);

      if(entityToAttack != null) {
        iattributeinstance.applyModifier(attackingSpeedBoostModifier);
      }
    }

    lastEntityToAttack = entityToAttack;
    for (int k = 0; k < 2; ++k) {
      worldObj.spawnParticle("portal", posX + (rand.nextDouble() - 0.5D) * width, posY + rand.nextDouble()
          * height - 0.25D, posZ + (rand.nextDouble() - 0.5D) * width, (rand.nextDouble() - 0.5D) * 2.0D,
          -rand.nextDouble(), (rand.nextDouble() - 0.5D) * 2.0D);
    }

    if(isBurning() || isInWater()) {
      entityToAttack = null;
      setScreaming(false);
      isAggressive = false;
      teleportRandomly();
    }

    if(isScreaming() && !isAggressive && rand.nextInt(100) == 0) {
      setScreaming(false);
    }

    isJumping = false;

    if(entityToAttack != null) {
      faceEntity(entityToAttack, 100.0F, 100.0F);
    }

    if(!worldObj.isRemote && isEntityAlive()) {
      if(entityToAttack != null) {
        if(entityToAttack.getDistanceSqToEntity(this) > 256.0D && teleportDelay++ >= 30 && teleportToEntity(entityToAttack)) {
          teleportDelay = 0;
        }
      } else {
        setScreaming(false);
        teleportDelay = 0;
      }
    }

    super.onLivingUpdate();
  }

  protected boolean teleportRandomly(int distance) {
    double d0 = posX + (rand.nextDouble() - 0.5D) * distance;
    double d1 = posY + rand.nextInt(distance + 1) - distance / 2;
    double d2 = posZ + (rand.nextDouble() - 0.5D) * distance;
    return teleportTo(d0, d1, d2);
  }

  protected boolean teleportRandomly() {
    return teleportRandomly(MAX_RND_TP_DISTANCE);
  }

  protected boolean teleportToEntity(Entity p_70816_1_) {
    Vec3 vec3 = Vec3.createVectorHelper(posX - p_70816_1_.posX, boundingBox.minY + height / 2.0F - p_70816_1_.posY
        + p_70816_1_.getEyeHeight(), posZ - p_70816_1_.posZ);
    vec3 = vec3.normalize();
    double d0 = 16.0D;
    double d1 = posX + (rand.nextDouble() - 0.5D) * 8.0D - vec3.xCoord * d0;
    double d2 = posY + (rand.nextInt(16) - 8) - vec3.yCoord * d0;
    double d3 = posZ + (rand.nextDouble() - 0.5D) * 8.0D - vec3.zCoord * d0;
    return teleportTo(d1, d2, d3);
  }

  protected boolean teleportTo(double x, double y, double z) {

    EnderTeleportEvent event = new EnderTeleportEvent(this, x, y, z, 0);
    if(MinecraftForge.EVENT_BUS.post(event)) {
      return false;
    }
    double d3 = posX;
    double d4 = posY;
    double d5 = posZ;
    posX = event.targetX;
    posY = event.targetY;
    posZ = event.targetZ;

    int xInt = MathHelper.floor_double(posX);
    int yInt = MathHelper.floor_double(posY);
    int zInt = MathHelper.floor_double(posZ);

    boolean flag = false;
    if(worldObj.blockExists(xInt, yInt, zInt)) {

      boolean foundGround = false;
      while (!foundGround && yInt > 0) {
        Block block = worldObj.getBlock(xInt, yInt - 1, zInt);
        if(block.getMaterial().blocksMovement()) {
          foundGround = true;
        } else {
          --posY;
          --yInt;
        }
      }

      if(foundGround) {
        setPosition(posX, posY, posZ);
        if(worldObj.getCollidingBoundingBoxes(this, boundingBox).isEmpty() && !worldObj.isAnyLiquid(boundingBox)) {
          flag = true;
        }
      }
    }

    if(!flag) {
      setPosition(d3, d4, d5);
      return false;
    }

    short short1 = 128;
    for (int l = 0; l < short1; ++l) {
      double d6 = l / (short1 - 1.0D);
      float f = (rand.nextFloat() - 0.5F) * 0.2F;
      float f1 = (rand.nextFloat() - 0.5F) * 0.2F;
      float f2 = (rand.nextFloat() - 0.5F) * 0.2F;
      double d7 = d3 + (posX - d3) * d6 + (rand.nextDouble() - 0.5D) * width * 2.0D;
      double d8 = d4 + (posY - d4) * d6 + rand.nextDouble() * height;
      double d9 = d5 + (posZ - d5) * d6 + (rand.nextDouble() - 0.5D) * width * 2.0D;
      worldObj.spawnParticle("portal", d7, d8, d9, f, f1, f2);
    }

    worldObj.playSoundEffect(d3, d4, d5, "mob.endermen.portal", 1.0F, 1.0F);
    playSound("mob.endermen.portal", 1.0F, 1.0F);
    return true;

  }

  @Override
  protected String getLivingSound() {
    return isScreaming() ? "mob.endermen.scream" : "mob.endermen.idle";
  }

  @Override
  protected String getHurtSound() {
    return "mob.endermen.hit";
  }

  @Override
  protected String getDeathSound() {
    return "mob.endermen.death";
  }

  @Override
  protected Item getDropItem() {
    return Items.ender_pearl;
  }

  @Override
  protected void dropFewItems(boolean hitByPlayer, int looting) {
    Item item = getDropItem();
    if(item != null) {
      int numItems = rand.nextInt(2 + looting);
      for (int i = 0; i < numItems; ++i) {
        if(rand.nextFloat() <= 0.5) {
          dropItem(EnderZoo.itemEnderFragment, 1);
        }
        dropItem(item, 1);
      }
    }
  }

  /**
   * Called when the entity is attacked.
   */
  @Override
  public boolean attackEntityFrom(DamageSource damageSource, float p_70097_2_) {
    if(isEntityInvulnerable()) {
      return false;
    }

    setScreaming(true);

    if(damageSource instanceof EntityDamageSourceIndirect) {
      isAggressive = false;
      for (int i = 0; i < 64; ++i) {
        if(teleportRandomly()) {
          return true;
        }
      }
      return super.attackEntityFrom(damageSource, p_70097_2_);
    }

    boolean res = super.attackEntityFrom(damageSource, p_70097_2_);
    if(damageSource instanceof EntityDamageSource && damageSource.getEntity() instanceof EntityPlayer &&
        getHealth() > 0 
        //&& !ItemDarkSteelSword.isEquippedAndPowered((EntityPlayer) damageSource.getEntity(), 1)) {
        ) {
      isAggressive = true;
      if(rand.nextInt(3) == 0) {
        for (int i = 0; i < 64; ++i) {
          if(teleportRandomly(16)) {
            entityToAttack = damageSource.getEntity();
            doGroupArgo();
            return true;
          }
        }
      }
    }

    if(res) {
      doGroupArgo();
    }
    return res;

  }

  private void doGroupArgo() {
    if(!groupAgroEnabled) {
      return;
    }
    if(!(entityToAttack instanceof EntityPlayer)) {
      return;
    }
    int range = 16;
    AxisAlignedBB bb = AxisAlignedBB.getBoundingBox(posX - range, posY - range, posZ - range, posX + range, posY + range, posZ + range);
    List<EntityEnderminy> minies = worldObj.getEntitiesWithinAABB(EntityEnderminy.class, bb);
    if(minies != null && !minies.isEmpty()) {

      for (EntityEnderminy miny : minies) {
        if(miny.entityToAttack == null) { //&& miny.canEntityBeSeen(this)) {
          miny.entityToAttack = entityToAttack;
        }
      }
    }
  }

  public boolean isScreaming() {
    return dataWatcher.getWatchableObjectByte(18) > 0;
  }

  public void setScreaming(boolean p_70819_1_) {
    dataWatcher.updateObject(18, Byte.valueOf((byte) (p_70819_1_ ? 1 : 0)));
  }

  private final class ClosestEntityComparator implements Comparator<EntityCreeper> {
    
    Vec3 pos = Vec3.createVectorHelper(0, 0, 0);

    @Override
    public int compare(EntityCreeper o1, EntityCreeper o2) {
      VecUtil.set(pos, posX, posY, posZ);      
      double d1 = distanceSquared(o1.posX, o1.posY, o1.posZ, pos);
      double d2 = distanceSquared(o2.posX, o2.posY, o2.posZ, pos);
      return Double.compare(d1, d2);
    }
  }
  
  public double distanceSquared(double x, double y, double z, Vec3 v2) {
    double dx, dy, dz;
    dx = x - v2.xCoord;
    dy = y - v2.yCoord;
    dz = z - v2.zCoord;
    return (dx * dx + dy * dy + dz * dz);
  }

}
